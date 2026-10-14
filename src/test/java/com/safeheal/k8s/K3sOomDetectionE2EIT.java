package com.safeheal.k8s;

import com.safeheal.agent.AuditRecorder;
import com.safeheal.agent.IncidentIntake;
import com.safeheal.agent.OomDetector;
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
import io.fabric8.kubernetes.api.model.PodList;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
        registry.add("safeheal.mode", () -> "IN_CLUSTER"); // For app context boot in test
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

        // Validate that K3s context passes ClusterContextGuard through configuration, not a bypass
        String activeContext = config.getCurrentContext() != null ? config.getCurrentContext().getName() : "default";
        List<String> allowedContexts = List.of(activeContext, "kind-safeheal-dev");
        assertDoesNotThrow(() -> ClusterContextGuard.validateContext(
            SafeHealProperties.Mode.KUBECONFIG,
            activeContext,
            allowedContexts
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
    void oomKilledE2EFlow() throws Exception {
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
            Clock.systemUTC()
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
                            // Allocates 10 million array strings in memory, exceeding 64Mi limit
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

        // 2. Wait for pod to be terminated with reason OOMKilled and exit 137
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(90);
        boolean oomObserved = false;
        Pod oomPod = null;

        while (System.currentTimeMillis() < deadline) {
            PodList pods = client.pods().inNamespace(TEST_NS).withLabel("app", "oom-app").list();
            if (!pods.getItems().isEmpty()) {
                oomPod = pods.getItems().getFirst();
                if (oomPod.getStatus() != null && oomPod.getStatus().getContainerStatuses() != null) {
                    var status = oomPod.getStatus().getContainerStatuses().getFirst();
                    var lastState = status.getLastState();
                    var state = status.getState();
                    if ((lastState != null && lastState.getTerminated() != null && "OOMKilled".equals(lastState.getTerminated().getReason()))
                        || (state != null && state.getTerminated() != null && "OOMKilled".equals(state.getTerminated().getReason()))) {
                        oomObserved = true;
                        break;
                    }
                }
            }
            Thread.sleep(1000);
        }

        assertTrue(oomObserved, "Expected pod to be OOMKilled within timeout");
        assertNotNull(oomPod);

        // Feed pod to informer directly in case of timing
        informerSource.handlePod(client.pods().inNamespace(TEST_NS).withName(oomPod.getMetadata().getName()).get());

        // 3. Assert exactly ONE incident is created in database
        deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15);
        int incidentCount = 0;
        while (System.currentTimeMillis() < deadline) {
            incidentCount = jdbcClient.sql("SELECT count(*) FROM incidents WHERE namespace = ? AND workload_name = ?")
                .params(TEST_NS, "oom-workload")
                .query(Integer.class)
                .single();
            if (incidentCount == 1) {
                break;
            }
            Thread.sleep(500);
        }

        assertEquals(1, incidentCount, "Expected exactly ONE incident created in DB for OOMKilled workload");

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

        // 4. Second crash of the same workload: simulate another crash event
        Pod updatedOomPod = client.pods().inNamespace(TEST_NS).withName(oomPod.getMetadata().getName()).get();
        informerSource.handlePod(updatedOomPod);

        int countAfterSecondCrash = jdbcClient.sql("SELECT count(*) FROM incidents WHERE namespace = ? AND workload_name = ?")
            .params(TEST_NS, "oom-workload")
            .query(Integer.class)
            .single();
        assertEquals(1, countAfterSecondCrash, "Second crash must NOT create a second incident row");

        // 5. Deploy non-OOM crashing workload (sh -c 'exit 1')
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

        // Wait for non-OOM pod to crash
        deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(45);
        Pod nonOomPod = null;
        while (System.currentTimeMillis() < deadline) {
            PodList pods = client.pods().inNamespace(TEST_NS).withLabel("app", "non-oom-app").list();
            if (!pods.getItems().isEmpty()) {
                nonOomPod = pods.getItems().getFirst();
                if (nonOomPod.getStatus() != null && nonOomPod.getStatus().getContainerStatuses() != null) {
                    var status = nonOomPod.getStatus().getContainerStatuses().getFirst();
                    if (status.getRestartCount() != null && status.getRestartCount() >= 1) {
                        break;
                    }
                }
            }
            Thread.sleep(1000);
        }

        assertNotNull(nonOomPod);
        informerSource.handlePod(client.pods().inNamespace(TEST_NS).withName(nonOomPod.getMetadata().getName()).get());

        // 6. Assert non-OOM workload creates ZERO incidents
        int nonOomCount = jdbcClient.sql("SELECT count(*) FROM incidents WHERE namespace = ? AND workload_name = ?")
            .params(TEST_NS, "non-oom-workload")
            .query(Integer.class)
            .single();
        assertEquals(0, nonOomCount, "Non-OOM crashing workload must create 0 incidents");
    }
}
