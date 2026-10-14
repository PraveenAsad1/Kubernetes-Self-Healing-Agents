package com.safeheal.k8s;

import com.safeheal.agent.DetectedFailure;
import com.safeheal.agent.IncidentIntake;
import com.safeheal.agent.OomDetector;
import com.safeheal.agent.TriageRouter;
import com.safeheal.incident.Incident;
import com.safeheal.incident.IncidentRepository;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerStateBuilder;
import io.fabric8.kubernetes.api.model.ContainerStateTerminatedBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@EnableKubernetesMockClient(crud = true)
class PodInformerSourceTest {

    private KubernetesMockServer server;
    private KubernetesClient client;

    private FabricClusterReader clusterReader;
    private OomDetector detector;
    private PodInformerSource source;

    private static final String ALLOWED_NS = "prod";
    private static final String OTHER_NS = "other";

    @BeforeEach
    void setUp() {
        clusterReader = new FabricClusterReader(client);
        detector = new OomDetector(Clock.systemUTC(), Duration.ofMinutes(15), Duration.ofMinutes(2), Set.of(ALLOWED_NS));
    }

    @AfterEach
    void tearDown() {
        if (source != null) {
            source.close();
        }
    }

    private void createDeploymentAndReplicaSet(String ns, String depName, String rsName) {
        Deployment dep = new DeploymentBuilder()
            .withMetadata(new ObjectMetaBuilder().withName(depName).withNamespace(ns).build())
            .build();
        Deployment createdDep = client.apps().deployments().inNamespace(ns).resource(dep).create();

        ReplicaSet rs = new ReplicaSetBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName(rsName)
                .withNamespace(ns)
                .withOwnerReferences(new OwnerReferenceBuilder()
                    .withKind("Deployment")
                    .withName(depName)
                    .withUid(createdDep.getMetadata().getUid())
                    .build())
                .build())
            .build();
        client.apps().replicaSets().inNamespace(ns).resource(rs).create();
    }

    private Pod createOomPod(String ns, String podName, String rsName, int restartCount) {
        var rs = client.apps().replicaSets().inNamespace(ns).withName(rsName).get();
        return new PodBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName(podName)
                .withNamespace(ns)
                .withUid("uid-" + podName)
                .withOwnerReferences(new OwnerReferenceBuilder()
                    .withKind("ReplicaSet")
                    .withName(rsName)
                    .withUid(rs.getMetadata().getUid())
                    .build())
                .build())
            .withStatus(new PodStatusBuilder()
                .withContainerStatuses(new ContainerStatusBuilder()
                    .withName("app")
                    .withRestartCount(restartCount)
                    .withLastState(new ContainerStateBuilder()
                        .withTerminated(new ContainerStateTerminatedBuilder()
                            .withExitCode(137)
                            .withReason("OOMKilled")
                            .withFinishedAt(Instant.now().toString())
                            .build())
                        .build())
                    .build())
                .build())
            .build();
    }

    @Test
    @DisplayName("Empty allowlist starts no informers (fails closed)")
    void emptyAllowlistStartsNoInformers() {
        IncidentIntake intake = new IncidentIntake(new StubRepo(), new TriageRouter(), Clock.systemUTC(), null);
        source = new PodInformerSource(client, clusterReader, detector, intake, Set.of(), Clock.systemUTC());
        source.start();

        assertTrue(source.getInformers().isEmpty());
    }

    @Test
    @DisplayName("OOMKilled pod added/updated reaches intake exactly once with NO manual handlePod")
    void oomPodReachesIntakeAutomatically() {
        AtomicInteger intakeCreatedCount = new AtomicInteger(0);
        IncidentIntake intake = new IncidentIntake(new StubRepo(), new TriageRouter(), Clock.systemUTC(), null) {
            @Override
            public Optional<Incident> intake(DetectedFailure failure) {
                Optional<Incident> res = super.intake(failure);
                if (res.isPresent()) {
                    intakeCreatedCount.incrementAndGet();
                }
                return res;
            }
        };

        createDeploymentAndReplicaSet(ALLOWED_NS, "web-dep", "web-rs");

        source = new PodInformerSource(
            client, clusterReader, detector, intake,
            Set.of(ALLOWED_NS), Clock.systemUTC(),
            Duration.ofSeconds(60), Duration.ofMinutes(2)
        );
        source.start();

        // 1. Add OOM pod to cluster: reaches intake automatically without manual handlePod
        Pod pod = createOomPod(ALLOWED_NS, "web-pod-1", "web-rs", 1);
        client.pods().inNamespace(ALLOWED_NS).resource(pod).create();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertEquals(1, intakeCreatedCount.get()));

        // 2. Update pod in cluster (e.g. metadata label change): deduplication prevents duplicate incident
        pod.getMetadata().setLabels(Map.of("updated", "true"));
        client.pods().inNamespace(ALLOWED_NS).resource(pod).update();

        await().pollDelay(500, TimeUnit.MILLISECONDS).until(() -> true);
        assertEquals(1, intakeCreatedCount.get(), "Pod update with same termination should not create duplicate incident");
    }

    @Test
    @DisplayName("Pod in non-allowlisted namespace never reaches intake")
    void nonAllowlistedNamespaceNeverReachesIntake() {
        List<DetectedFailure> received = new ArrayList<>();
        IncidentIntake intake = new IncidentIntake(new StubRepo(), new TriageRouter(), Clock.systemUTC(), null) {
            @Override
            public Optional<Incident> intake(DetectedFailure failure) {
                received.add(failure);
                return super.intake(failure);
            }
        };

        createDeploymentAndReplicaSet(OTHER_NS, "other-dep", "other-rs");

        source = new PodInformerSource(
            client, clusterReader, detector, intake,
            Set.of(ALLOWED_NS), Clock.systemUTC(),
            Duration.ofSeconds(60), Duration.ofMinutes(2)
        );
        source.start();

        Pod pod = createOomPod(OTHER_NS, "other-pod-1", "other-rs", 1);
        client.pods().inNamespace(OTHER_NS).resource(pod).create();

        await().pollDelay(1, TimeUnit.SECONDS).until(() -> true);
        assertTrue(received.isEmpty(), "Pod in non-allowlisted namespace should not reach intake");
    }

    @Test
    @DisplayName("Failing intake is logged and retried on resync")
    void failingIntakeLoggedAndRetriedOnResync() {
        AtomicBoolean firstAttempt = new AtomicBoolean(true);
        AtomicInteger successCount = new AtomicInteger(0);

        IncidentIntake intake = new IncidentIntake(new StubRepo(), new TriageRouter(), Clock.systemUTC(), null) {
            @Override
            public Optional<Incident> intake(DetectedFailure failure) {
                if (firstAttempt.compareAndSet(true, false)) {
                    throw new RuntimeException("Simulated transient processing failure");
                }
                successCount.incrementAndGet();
                return super.intake(failure);
            }
        };

        createDeploymentAndReplicaSet(ALLOWED_NS, "retry-dep", "retry-rs");

        // Set resync to 500ms for quick retry
        source = new PodInformerSource(
            client, clusterReader, detector, intake,
            Set.of(ALLOWED_NS), Clock.systemUTC(),
            Duration.ofMillis(500), Duration.ofMinutes(2)
        );
        source.start();

        Pod pod = createOomPod(ALLOWED_NS, "retry-pod-1", "retry-rs", 1);
        client.pods().inNamespace(ALLOWED_NS).resource(pod).create();

        // 1. First attempt fails and increments failure counter
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertTrue(source.getFailureCount() >= 1));

        // 2. Resync triggers retry, which succeeds
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertEquals(1, successCount.get()));
    }

    static class StubRepo implements IncidentRepository {
        @Override
        public CreateResult create(Incident incident) {
            return new CreateResult.Created(incident);
        }
        @Override public Optional<Incident> findById(String id) { return Optional.empty(); }
        @Override public Incident applyTransition(Incident incident, com.safeheal.incident.IncidentEvent event, com.safeheal.incident.Transition transition) { return incident; }
        @Override public Optional<Incident> claimNext(String workerId, Duration leaseDuration, Instant now) { return Optional.empty(); }
        @Override public void heartbeat(String id, String workerId, Instant now, Duration leaseDuration) {}
        @Override public List<Incident> findResumable(Instant now) { return List.of(); }
        @Override public Instant cooldownUntil(String namespace, String workloadUid) { return null; }
        @Override public int appliedRemediationsSince(Instant since) { return 0; }
    }
}
