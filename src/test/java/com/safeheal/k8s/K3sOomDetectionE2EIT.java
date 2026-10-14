package com.safeheal.k8s;

import com.safeheal.agent.AuditRecorder;
import com.safeheal.agent.DetectedFailure;
import com.safeheal.agent.IncidentIntake;
import com.safeheal.agent.OomDetector;
import com.safeheal.agent.PodSnapshot;
import com.safeheal.agent.TriageRouter;
import com.safeheal.config.ClusterContextGuard;
import com.safeheal.config.SafeHealProperties;
import com.safeheal.incident.Incident;
import com.safeheal.incident.IncidentState;
import com.safeheal.persistence.JdbcIncidentRepository;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class K3sOomDetectionE2EIT {

    private static final String TEST_NS = "test-oom";
    private static final String PINNED_IMAGE = "alpine:3.20.3";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
        .withDatabaseName("safeheal")
        .withUsername("safeheal_owner")
        .withPassword("owner_password")
        .withCopyFileToContainer(MountableFile.forHostPath("infra/postgres/init-roles.sql"), "/docker-entrypoint-initdb.d/init-roles.sql");

    @Container
    static K3sContainer k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.31.2-k3s1"));

    static KubernetesClient client;
    static PodInformerSource informerSource;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "safeheal_app");
        registry.add("spring.datasource.password", () -> "app_password");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "safeheal_owner");
        registry.add("spring.flyway.password", () -> "owner_password");
        registry.add("safeheal.mode", () -> "IN_CLUSTER"); // For Spring context boot in test
    }

    @Autowired
    private JdbcIncidentRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void setUpAll() {
        String kubeconfigYaml = k3s.getKubeConfigYaml();
        Config config = Config.fromKubeconfig(kubeconfigYaml);
        client = new KubernetesClientBuilder().withConfig(config).build();

        String activeContext = config.getCurrentContext() != null ? config.getCurrentContext().getName() : "default";

        // Assert startup FAILS when k3s context is not in allowlist
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ClusterContextGuard.validateContext(
            SafeHealProperties.Mode.KUBECONFIG,
            activeContext,
            List.of("disallowed-context-999")
        ));
        assertTrue(ex.getMessage().contains("not in the allowlist"));

        // Assert passes when allowed through configuration
        assertDoesNotThrow(() -> ClusterContextGuard.validateContext(
            SafeHealProperties.Mode.KUBECONFIG,
            activeContext,
            List.of(activeContext, "kind-safeheal-dev")
        ));

        // Create test namespace
        client.namespaces().resource(new NamespaceBuilder()
            .withMetadata(new ObjectMetaBuilder().withName(TEST_NS).build())
            .build()).createOrReplace();
    }

    @AfterAll
    static void tearDownAll() {
        if (informerSource != null) {
            informerSource.close();
        }
        if (client != null) {
            client.close();
        }
    }

    @Test
    @DisplayName("E2E: OOMKilled workload creates exactly ONE incident; restart creates none; non-OOM creates none")
    void oomKilledE2EFlow() {
        FabricClusterReader clusterReader = new FabricClusterReader(client);
        OomDetector detector = new OomDetector(
            Clock.systemUTC(),
            Duration.ofMinutes(15),
            Duration.ofMinutes(2),
            Set.of(TEST_NS)
        );
        AuditRecorder auditRecorder = (id, type, payload, ts) -> {
            jdbcClient.sql("INSERT INTO audit_events (incident_id, event_type, payload, timestamp) VALUES (?, ?, ?, ?)")
                .params(id, type, payload, java.sql.Timestamp.from(ts))
                .update();
        };
        IncidentIntake intake = new IncidentIntake(
            repository,
            new TriageRouter(),
            Clock.systemUTC(),
            auditRecorder
        );

        informerSource = new PodInformerSource(
            client,
            clusterReader,
            detector,
            intake,
            Set.of(TEST_NS),
            Clock.systemUTC(),
            Duration.ofSeconds(2), // 2s resync for fast E2E observation
            Duration.ofMinutes(2)
        );
        informerSource.start();

        // 1. Deploy OOMKilled workload: memory limit 64Mi, allocation clearly above 64Mi
        Deployment oomDeployment = new DeploymentBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("oom-workload").withNamespace(TEST_NS).build())
            .withSpec(new io.fabric8.kubernetes.api.model.apps.DeploymentSpecBuilder()
                .withReplicas(1)
                .withSelector(new io.fabric8.kubernetes.api.model.LabelSelectorBuilder()
                    .withMatchLabels(Map.of("app", "oom-app"))
                    .build())
                .withTemplate(new io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder()
                    .withMetadata(new ObjectMetaBuilder().withLabels(Map.of("app", "oom-app")).build())
                    .withSpec(new io.fabric8.kubernetes.api.model.PodSpecBuilder()
                        .withRestartPolicy("Always")
                        .withContainers(new ContainerBuilder()
                            .withName("oom-container")
                            .withImage(PINNED_IMAGE)
                            // Allocates array strings in memory until OOM
                            .withCommand("awk", "BEGIN{for(i=0;i<10000000;i++) a[i]=\"abcdefghijklmnopqrstuvwxyz\"}")
                            .withResources(new ResourceRequirementsBuilder()
                                .withRequests(Map.of("memory", new Quantity("32Mi")))
                                .withLimits(Map.of("memory", new Quantity("64Mi")))
                                .build())
                            .build())
                        .build())
                    .build())
                .build())
            .build();

        client.apps().deployments().inNamespace(TEST_NS).resource(oomDeployment).create();

        // 2. Awaitility: wait for pod to be terminated with OOMKilled and exit 137
        await().atMost(90, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(() -> {
            var pods = client.pods().inNamespace(TEST_NS).withLabel("app", "oom-app").list().getItems();
            if (pods.isEmpty()) return false;
            Pod p = pods.getFirst();
            if (p.getStatus() == null || p.getStatus().getContainerStatuses() == null || p.getStatus().getContainerStatuses().isEmpty()) {
                return false;
            }
            var status = p.getStatus().getContainerStatuses().getFirst();
            var lastState = status.getLastState();
            var state = status.getState();
            return (lastState != null && lastState.getTerminated() != null && "OOMKilled".equals(lastState.getTerminated().getReason()))
                || (state != null && state.getTerminated() != null && "OOMKilled".equals(state.getTerminated().getReason()));
        });

        // 3. Awaitility: wait for informer to automatically process the OOM pod and create exactly ONE incident in DB
        await().atMost(15, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            int count = jdbcClient.sql("SELECT count(*) FROM incidents WHERE namespace = ? AND workload_name = ?")
                .params(TEST_NS, "oom-workload")
                .query(Integer.class)
                .single();
            assertEquals(1, count);
        });

        Incident created = jdbcClient.sql("SELECT * FROM incidents WHERE namespace = ? AND workload_name = ?")
            .params(TEST_NS, "oom-workload")
            .query((rs, n) -> new Incident(
                rs.getString("id"),
                rs.getString("namespace"),
                rs.getString("workload_uid"),
                rs.getString("workload_name"),
                rs.getString("agent_type"),
                IncidentState.valueOf(rs.getString("state")),
                rs.getInt("attempt"),
                rs.getInt("transient_retries"),
                rs.getInt("version"),
                rs.getString("claimed_by"),
                null,
                null,
                null,
                null
            )).single();

        assertEquals(IncidentState.DETECTED, created.state());
        assertEquals("oom", created.agentType());

        // 4. Second crash: wait until restartCount increases (a real second OOM crash)
        await().atMost(90, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(() -> {
            var pods = client.pods().inNamespace(TEST_NS).withLabel("app", "oom-app").list().getItems();
            if (pods.isEmpty()) return false;
            var status = pods.getFirst().getStatus().getContainerStatuses().getFirst();
            return status.getRestartCount() != null && status.getRestartCount() >= 2;
        });

        // Assert still exactly ONE incident row in DB
        int countAfterSecondCrash = jdbcClient.sql("SELECT count(*) FROM incidents WHERE namespace = ? AND workload_name = ?")
            .params(TEST_NS, "oom-workload")
            .query(Integer.class)
            .single();
        assertEquals(1, countAfterSecondCrash, "Second real OOM crash must NOT create a second incident row");

        // 5. Test using a FRESH IncidentIntake (empty dedupe) against the same DB asserting unique index yields Duplicate
        Pod currentOomPod = client.pods().inNamespace(TEST_NS).withLabel("app", "oom-app").list().getItems().getFirst();
        PodSnapshot snapshot = clusterReader.toSnapshot(currentOomPod);
        DetectedFailure failure = detector.detect(snapshot).orElseThrow();

        IncidentIntake freshIntake = new IncidentIntake(repository, new TriageRouter(), Clock.systemUTC(), null);
        Optional<Incident> duplicateResult = freshIntake.intake(failure);
        assertTrue(duplicateResult.isEmpty(), "Fresh intake against existing active workload must yield Duplicate from DB and return empty");

        int totalIncidentCount = jdbcClient.sql("SELECT count(*) FROM incidents WHERE namespace = ? AND workload_name = ?")
            .params(TEST_NS, "oom-workload")
            .query(Integer.class)
            .single();
        assertEquals(1, totalIncidentCount, "Database unique index must keep count at exactly 1");

        // 6. Deploy non-OOM crashing workload (sh -c 'exit 1')
        Deployment nonOomDeployment = new DeploymentBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("non-oom-workload").withNamespace(TEST_NS).build())
            .withSpec(new io.fabric8.kubernetes.api.model.apps.DeploymentSpecBuilder()
                .withReplicas(1)
                .withSelector(new io.fabric8.kubernetes.api.model.LabelSelectorBuilder()
                    .withMatchLabels(Map.of("app", "non-oom-app"))
                    .build())
                .withTemplate(new io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder()
                    .withMetadata(new ObjectMetaBuilder().withLabels(Map.of("app", "non-oom-app")).build())
                    .withSpec(new io.fabric8.kubernetes.api.model.PodSpecBuilder()
                        .withRestartPolicy("Always")
                        .withContainers(new ContainerBuilder()
                            .withName("error-container")
                            .withImage(PINNED_IMAGE)
                            .withCommand("sh", "-c", "exit 1")
                            .build())
                        .build())
                    .build())
                .build())
            .build();

        client.apps().deployments().inNamespace(TEST_NS).resource(nonOomDeployment).create();

        // Wait with Awaitility for non-OOM pod to crash
        await().atMost(45, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(() -> {
            var pods = client.pods().inNamespace(TEST_NS).withLabel("app", "non-oom-app").list().getItems();
            if (pods.isEmpty()) return false;
            Pod p = pods.getFirst();
            if (p.getStatus() == null || p.getStatus().getContainerStatuses() == null || p.getStatus().getContainerStatuses().isEmpty()) {
                return false;
            }
            var status = p.getStatus().getContainerStatuses().getFirst();
            return status.getRestartCount() != null && status.getRestartCount() >= 1;
        });

        // 7. Assert non-OOM workload creates ZERO incidents
        int nonOomCount = jdbcClient.sql("SELECT count(*) FROM incidents WHERE namespace = ? AND workload_name = ?")
            .params(TEST_NS, "non-oom-workload")
            .query(Integer.class)
            .single();
        assertEquals(0, nonOomCount, "Non-OOM crashing workload must create 0 incidents");
    }
}
