package com.safeheal.k8s;

import com.safeheal.agent.PodSnapshot;
import com.safeheal.agent.WorkloadRef;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerStateBuilder;
import io.fabric8.kubernetes.api.model.ContainerStateTerminatedBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@EnableKubernetesMockClient(crud = true)
class FabricClusterReaderTest {

    private KubernetesMockServer server;
    private KubernetesClient client;
    private FabricClusterReader reader;

    private static final String NS = "prod";

    @BeforeEach
    void setUp() {
        reader = new FabricClusterReader(client);
    }

    @Test
    @DisplayName("Resolves owner chain Pod -> ReplicaSet -> Deployment")
    void resolvesPodToReplicaSetToDeployment() {
        Deployment dep = new DeploymentBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("web-deployment").withNamespace(NS).build())
            .build();
        Deployment createdDep = client.apps().deployments().inNamespace(NS).resource(dep).create();

        ReplicaSet rs = new ReplicaSetBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName("web-rs-1")
                .withNamespace(NS)
                .withOwnerReferences(new OwnerReferenceBuilder()
                    .withKind("Deployment")
                    .withName("web-deployment")
                    .withUid(createdDep.getMetadata().getUid())
                    .build())
                .build())
            .build();
        ReplicaSet createdRs = client.apps().replicaSets().inNamespace(NS).resource(rs).create();

        Pod pod = new PodBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName("web-pod-abc")
                .withNamespace(NS)
                .withOwnerReferences(new OwnerReferenceBuilder()
                    .withKind("ReplicaSet")
                    .withName("web-rs-1")
                    .withUid(createdRs.getMetadata().getUid())
                    .build())
                .build())
            .build();
        client.pods().inNamespace(NS).resource(pod).create();

        WorkloadRef resolved = reader.resolveOwnerWorkload(pod);
        assertNotNull(resolved);
        assertEquals("Deployment", resolved.kind());
        assertEquals("web-deployment", resolved.name());
        assertEquals(createdDep.getMetadata().getUid(), resolved.uid());
        assertEquals(NS, resolved.namespace());
    }

    @Test
    @DisplayName("Bare pod without owner references is ineligible")
    void barePodIsIneligible() {
        Pod pod = new PodBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName("bare-pod")
                .withNamespace(NS)
                .withUid("pod-bare-uid")
                .build())
            .build();

        assertNull(reader.resolveOwnerWorkload(pod));
    }

    @Test
    @DisplayName("Job pod is identified as Job workload")
    void jobPodIsIdentifiedAsJob() {
        Pod pod = new PodBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName("job-pod")
                .withNamespace(NS)
                .withUid("pod-job-uid")
                .withOwnerReferences(new OwnerReferenceBuilder()
                    .withKind("Job")
                    .withName("batch-job")
                    .withUid("job-uid-1")
                    .build())
                .build())
            .build();

        WorkloadRef resolved = reader.resolveOwnerWorkload(pod);
        assertNotNull(resolved);
        assertEquals("Job", resolved.kind());
        assertEquals("batch-job", resolved.name());
    }

    @Test
    @DisplayName("Pod owned by ownerless ReplicaSet is ineligible")
    void ownerlessReplicaSetIsIneligible() {
        ReplicaSet rs = new ReplicaSetBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName("orphan-rs")
                .withNamespace(NS)
                .withUid("rs-orphan-uid")
                .build())
            .build();
        client.apps().replicaSets().inNamespace(NS).resource(rs).create();

        Pod pod = new PodBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName("pod-with-orphan-rs")
                .withNamespace(NS)
                .withUid("pod-orphan-uid")
                .withOwnerReferences(new OwnerReferenceBuilder()
                    .withKind("ReplicaSet")
                    .withName("orphan-rs")
                    .withUid("rs-orphan-uid")
                    .build())
                .build())
            .build();

        assertNull(reader.resolveOwnerWorkload(pod));
    }

    @Test
    @DisplayName("Deleted Deployment causes resolution to return null")
    void deletedDeploymentIsIneligible() {
        Deployment dep = new DeploymentBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName("dying-deployment")
                .withNamespace(NS)
                .build())
            .build();
        client.apps().deployments().inNamespace(NS).resource(dep).create();
        client.apps().deployments().inNamespace(NS).withName("dying-deployment").delete();

        ReplicaSet rs = new ReplicaSetBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName("dying-rs")
                .withNamespace(NS)
                .withUid("rs-dying-uid")
                .withOwnerReferences(new OwnerReferenceBuilder()
                    .withKind("Deployment")
                    .withName("dying-deployment")
                    .withUid("dep-dying-uid")
                    .build())
                .build())
            .build();
        client.apps().replicaSets().inNamespace(NS).resource(rs).create();

        Pod pod = new PodBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName("pod-dying")
                .withNamespace(NS)
                .withUid("pod-dying-uid")
                .withOwnerReferences(new OwnerReferenceBuilder()
                    .withKind("ReplicaSet")
                    .withName("dying-rs")
                    .withUid("rs-dying-uid")
                    .build())
                .build())
            .build();

        assertNull(reader.resolveOwnerWorkload(pod));
    }

    @Test
    @DisplayName("toSnapshot extracts termination from lastState and state")
    void toSnapshotExtractsTerminations() {
        Pod pod = new PodBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName("oom-pod")
                .withNamespace(NS)
                .withUid("oom-pod-uid")
                .build())
            .withSpec(new io.fabric8.kubernetes.api.model.PodSpecBuilder()
                .withContainers(new ContainerBuilder()
                    .withName("app")
                    .withImage("app:latest")
                    .build())
                .build())
            .withStatus(new PodStatusBuilder()
                .withContainerStatuses(new ContainerStatusBuilder()
                    .withName("app")
                    .withRestartCount(3)
                    .withLastState(new ContainerStateBuilder()
                        .withTerminated(new ContainerStateTerminatedBuilder()
                            .withExitCode(137)
                            .withReason("OOMKilled")
                            .withFinishedAt("2026-10-14T12:00:00Z")
                            .build())
                        .build())
                    .build())
                .build())
            .build();

        PodSnapshot snapshot = reader.toSnapshot(pod);
        assertNotNull(snapshot);
        assertEquals("oom-pod", snapshot.name());
        assertEquals(1, snapshot.appContainerTerminations().size());
        var term = snapshot.appContainerTerminations().getFirst();
        assertEquals("app", term.containerName());
        assertEquals("OOMKilled", term.reason());
        assertEquals(137, term.exitCode());
        assertEquals(3, term.restartCount());
        assertNotNull(term.finishedAt());
    }

    @Test
    @DisplayName("events query fetches matching involvedObject events")
    void eventsQueryFetchesInvolvedObjectEvents() {
        client.v1().events().inNamespace(NS).resource(new EventBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("event-1").withNamespace(NS).build())
            .withInvolvedObject(new ObjectReferenceBuilder().withUid("target-uid").build())
            .withType("Warning")
            .withReason("OOMKilled")
            .withMessage("Container app killed by OOM killer")
            .build()).create();

        List<String> events = reader.events(NS, "target-uid");
        assertEquals(1, events.size());
        assertTrue(events.getFirst().contains("OOMKilled"));
    }
}
