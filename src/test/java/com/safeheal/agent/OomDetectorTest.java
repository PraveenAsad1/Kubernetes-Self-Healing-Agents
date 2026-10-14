package com.safeheal.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OomDetectorTest {

    private static final Instant BASE_TIME = Instant.parse("2026-10-14T12:00:00Z");
    private Clock fixedClock;
    private OomDetector detector;
    private final Set<String> allowlistedNamespaces = Set.of("default", "prod", "payments");

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(BASE_TIME, ZoneOffset.UTC);
        detector = new OomDetector(fixedClock, Duration.ofMinutes(10), Duration.ofMinutes(2), allowlistedNamespaces);
    }

    private PodSnapshot createSnapshot(
        String namespace,
        WorkloadRef owner,
        List<ContainerTermination> appTerminations,
        List<ContainerTermination> initTerminations,
        boolean deleted
    ) {
        return new PodSnapshot(
            "test-pod-xyz",
            namespace,
            "uid-pod-123",
            owner,
            Map.of("app", "test"),
            appTerminations,
            initTerminations,
            List.of(),
            deleted
        );
    }

    @Test
    @DisplayName("Valid OOMKilled deployment pod matches")
    void validOomKilledPodMatches() {
        WorkloadRef deployment = new WorkloadRef("payments", "Deployment", "payment-service", "dep-uid-1");
        ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, BASE_TIME.minusSeconds(60), 3);
        PodSnapshot snapshot = createSnapshot("payments", deployment, List.of(term), List.of(), false);

        Optional<DetectedFailure> result = detector.detect(snapshot);

        assertTrue(result.isPresent());
        DetectedFailure failure = result.get();
        assertEquals(deployment, failure.workload());
        assertEquals("test-pod-xyz", failure.podName());
        assertEquals("uid-pod-123", failure.podUid());
        assertEquals("app", failure.containerName());
        assertEquals(term, failure.termination());
        assertEquals(BASE_TIME, failure.detectedAt());
    }

    @ParameterizedTest(name = "reason={0}, exitCode={1} should be rejected")
    @CsvSource({
        "Error, 137",
        "ContainerCannotRun, 137",
        "Completed, 0",
        "Error, 1",
        "OOMKilled, 1",
        "OOMKilled, 143",
        "OOMKilled, 255"
    })
    void falsePositiveExitCodesAndReasonsRejected(String reason, int exitCode) {
        WorkloadRef deployment = new WorkloadRef("payments", "Deployment", "payment-service", "dep-uid-1");
        ContainerTermination term = new ContainerTermination("app", reason, exitCode, BASE_TIME.minusSeconds(30), 1);
        PodSnapshot snapshot = createSnapshot("payments", deployment, List.of(term), List.of(), false);

        Optional<DetectedFailure> result = detector.detect(snapshot);
        assertTrue(result.isEmpty(), "Expected failure to be rejected for reason=" + reason + " exitCode=" + exitCode);
    }

    @Test
    @DisplayName("Stale termination older than recency window is rejected")
    void staleTerminationRejected() {
        WorkloadRef deployment = new WorkloadRef("payments", "Deployment", "payment-service", "dep-uid-1");
        // 11 minutes ago (window is 10 min)
        ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, BASE_TIME.minus(Duration.ofMinutes(11)), 1);
        PodSnapshot snapshot = createSnapshot("payments", deployment, List.of(term), List.of(), false);

        assertTrue(detector.detect(snapshot).isEmpty());
    }

    @Test
    @DisplayName("Future termination within 2 min skew tolerance is accepted")
    void futureTerminationWithinSkewAccepted() {
        WorkloadRef deployment = new WorkloadRef("payments", "Deployment", "payment-service", "dep-uid-1");
        // 90 seconds in future (within 2 min skew)
        ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, BASE_TIME.plusSeconds(90), 1);
        PodSnapshot snapshot = createSnapshot("payments", deployment, List.of(term), List.of(), false);

        assertTrue(detector.detect(snapshot).isPresent());
    }

    @Test
    @DisplayName("Future termination beyond skew tolerance is rejected")
    void futureTerminationBeyondSkewRejected() {
        WorkloadRef deployment = new WorkloadRef("payments", "Deployment", "payment-service", "dep-uid-1");
        // 3 minutes in future (beyond 2 min skew)
        ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, BASE_TIME.plus(Duration.ofMinutes(3)), 1);
        PodSnapshot snapshot = createSnapshot("payments", deployment, List.of(term), List.of(), false);

        assertTrue(detector.detect(snapshot).isEmpty());
    }

    @Test
    @DisplayName("Bare pod without owner is rejected")
    void barePodRejected() {
        ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, BASE_TIME.minusSeconds(10), 1);
        PodSnapshot snapshot = createSnapshot("payments", null, List.of(term), List.of(), false);

        assertTrue(detector.detect(snapshot).isEmpty());
    }

    @Test
    @DisplayName("Job pod is rejected")
    void jobPodRejected() {
        WorkloadRef job = new WorkloadRef("payments", "Job", "batch-sync", "job-1");
        ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, BASE_TIME.minusSeconds(10), 1);
        PodSnapshot snapshot = createSnapshot("payments", job, List.of(term), List.of(), false);

        assertTrue(detector.detect(snapshot).isEmpty());
    }

    @Test
    @DisplayName("Standalone ReplicaSet without Deployment is rejected")
    void standaloneReplicaSetRejected() {
        WorkloadRef rs = new WorkloadRef("payments", "ReplicaSet", "unowned-rs", "rs-1");
        ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, BASE_TIME.minusSeconds(10), 1);
        PodSnapshot snapshot = createSnapshot("payments", rs, List.of(term), List.of(), false);

        assertTrue(detector.detect(snapshot).isEmpty());
    }

    @ParameterizedTest(name = "Hard denylisted namespace {0} must be rejected even when allowlisted")
    @CsvSource({"kube-system", "kube-public", "kube-node-lease"})
    void hardDenylistedNamespacesAlwaysRejected(String denylistedNs) {
        // Allowlist explicitly includes kube-system, kube-public, kube-node-lease and still rejects them
        OomDetector permissiveDetector = new OomDetector(fixedClock, Set.of("kube-system", "kube-public", "kube-node-lease", "payments"));
        WorkloadRef deployment = new WorkloadRef(denylistedNs, "Deployment", "coredns", "dns-1");
        ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, BASE_TIME.minusSeconds(10), 1);
        PodSnapshot snapshot = createSnapshot(denylistedNs, deployment, List.of(term), List.of(), false);

        assertTrue(permissiveDetector.detect(snapshot).isEmpty());
    }

    @Test
    @DisplayName("Mirror case: allowlisted and not denylisted namespace is accepted")
    void allowlistedNotDenylistedNamespaceAccepted() {
        OomDetector permissiveDetector = new OomDetector(fixedClock, Set.of("kube-system", "kube-public", "kube-node-lease", "payments"));
        WorkloadRef deployment = new WorkloadRef("payments", "Deployment", "payments-app", "pay-1");
        ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, BASE_TIME.minusSeconds(10), 1);
        PodSnapshot snapshot = createSnapshot("payments", deployment, List.of(term), List.of(), false);

        assertTrue(permissiveDetector.detect(snapshot).isPresent());
    }

    @Test
    @DisplayName("Non-allowlisted namespace is rejected")
    void nonAllowlistedNamespaceRejected() {
        WorkloadRef deployment = new WorkloadRef("other-ns", "Deployment", "other-app", "dep-2");
        ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, BASE_TIME.minusSeconds(10), 1);
        PodSnapshot snapshot = createSnapshot("other-ns", deployment, List.of(term), List.of(), false);

        assertTrue(detector.detect(snapshot).isEmpty());
    }

    @Test
    @DisplayName("Deleted workload is rejected")
    void deletedWorkloadRejected() {
        WorkloadRef deployment = new WorkloadRef("payments", "Deployment", "payment-service", "dep-uid-1");
        ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, BASE_TIME.minusSeconds(10), 1);
        PodSnapshot snapshot = createSnapshot("payments", deployment, List.of(term), List.of(), true);

        assertTrue(detector.detect(snapshot).isEmpty());
    }

    @Test
    @DisplayName("Init container OOM is ignored per ADR 22")
    void initContainerOomIgnored() {
        WorkloadRef deployment = new WorkloadRef("payments", "Deployment", "payment-service", "dep-uid-1");
        ContainerTermination initTerm = new ContainerTermination("db-migration", "OOMKilled", 137, BASE_TIME.minusSeconds(10), 1);
        PodSnapshot snapshot = createSnapshot("payments", deployment, List.of(), List.of(initTerm), false);

        assertTrue(detector.detect(snapshot).isEmpty());
    }
}
