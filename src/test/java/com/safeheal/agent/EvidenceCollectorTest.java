package com.safeheal.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceCollectorTest {

    static class StubClusterReader implements ClusterReader {
        boolean throwOnLogs = false;
        boolean throwOnEvents = false;

        @Override
        public Optional<WorkloadRef> readWorkload(String namespace, String kind, String name) {
            return Optional.of(new WorkloadRef(namespace, kind, name, "uid-1"));
        }

        @Override
        public List<PodSnapshot> podsOf(WorkloadRef workload) {
            return List.of();
        }

        @Override
        public Optional<String> previousLogs(String namespace, String podName, String containerName, int maxBytes) {
            if (throwOnLogs) {
                throw new ClusterReadException("K8s API 500 error fetching logs");
            }
            return Optional.of("Starting app...\npassword=mysecret\nFatal error: OOM");
        }

        @Override
        public List<String> events(String namespace, String resourceUid) {
            if (throwOnEvents) {
                throw new ClusterReadException("K8s API connection refused fetching events");
            }
            return List.of("Warning BackOff Back-off restarting failed container", "Normal Pulled Container image pulled");
        }
    }

    @Test
    @DisplayName("Collects redacted logs, events, and whitelisted pod specs without env vars")
    void collectsRedactedLogsAndWhitelistedSpecs() {
        StubClusterReader reader = new StubClusterReader();
        EvidenceCollector collector = new EvidenceCollector(reader, new Redactor());

        ContainerSpec spec = new ContainerSpec(
            "payment-app",
            "payments:v1.2",
            List.of("/bin/app", "--port=8080"),
            Map.of("cpu", "100m", "memory", "64Mi"),
            Map.of("cpu", "500m", "memory", "128Mi")
        );

        PodSnapshot pod = new PodSnapshot(
            "pod-1",
            "payments",
            "uid-pod-1",
            new WorkloadRef("payments", "Deployment", "payment-service", "dep-1"),
            Map.of(),
            List.of(),
            List.of(),
            List.of(spec),
            false
        );

        EvidenceCollector.CollectedEvidence evidence = collector.collect(pod, "payment-app", 50000);

        assertNotNull(evidence);
        assertEquals("pod-1", evidence.podName());
        assertEquals("payment-app", evidence.containerName());
        // Verify logs were redacted
        assertFalse(evidence.redactedLogs().contains("mysecret"));
        assertTrue(evidence.redactedLogs().contains("password: [REDACTED]"));
        // Verify events
        assertEquals(2, evidence.k8sEvents().size());
        // Verify specs whitelist
        assertEquals(1, evidence.whitelistedSpecs().size());
        ContainerSpec collectedSpec = evidence.whitelistedSpecs().getFirst();
        assertEquals("payment-app", collectedSpec.name());
        assertEquals("payments:v1.2", collectedSpec.image());
        assertEquals(List.of("/bin/app", "--port=8080"), collectedSpec.command());
        assertEquals("64Mi", collectedSpec.requests().get("memory"));
        assertEquals("128Mi", collectedSpec.limits().get("memory"));
    }

    @Test
    @DisplayName("ClusterReadException from previousLogs is propagated and not swallowed")
    void propagatesClusterReadExceptionOnLogs() {
        StubClusterReader reader = new StubClusterReader();
        reader.throwOnLogs = true;
        EvidenceCollector collector = new EvidenceCollector(reader, new Redactor());

        PodSnapshot pod = new PodSnapshot(
            "pod-1", "payments", "uid-pod-1",
            new WorkloadRef("payments", "Deployment", "payment-service", "dep-1"),
            Map.of(), List.of(), List.of(), List.of(), false
        );

        ClusterReadException ex = assertThrows(ClusterReadException.class, () -> collector.collect(pod, "app", 50000));
        assertTrue(ex.getMessage().contains("fetching logs"));
    }

    @Test
    @DisplayName("ClusterReadException from events is propagated and not swallowed")
    void propagatesClusterReadExceptionOnEvents() {
        StubClusterReader reader = new StubClusterReader();
        reader.throwOnEvents = true;
        EvidenceCollector collector = new EvidenceCollector(reader, new Redactor());

        PodSnapshot pod = new PodSnapshot(
            "pod-1", "payments", "uid-pod-1",
            new WorkloadRef("payments", "Deployment", "payment-service", "dep-1"),
            Map.of(), List.of(), List.of(), List.of(), false
        );

        ClusterReadException ex = assertThrows(ClusterReadException.class, () -> collector.collect(pod, "app", 50000));
        assertTrue(ex.getMessage().contains("fetching events"));
    }
}
