package com.safeheal.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TriageRouterTest {

    private final TriageRouter router = new TriageRouter();

    @Test
    @DisplayName("OOMKilled with exit 137 routes to agentType oom")
    void oomKilledRoutesToOom() {
        WorkloadRef workload = new WorkloadRef("prod", "Deployment", "web", "uid-1");
        ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, Instant.now(), 1);
        DetectedFailure failure = new DetectedFailure(workload, "web-pod-1", "app", term, Instant.now());

        Optional<String> agentType = router.route(failure);
        assertTrue(agentType.isPresent());
        assertEquals("oom", agentType.get());
    }

    @ParameterizedTest(name = "reason={0}, exitCode={1} should be ignored")
    @CsvSource({
        "Error, 137",
        "ContainerCannotRun, 137",
        "OOMKilled, 1",
        "OOMKilled, 143",
        "Error, 1",
        "Completed, 0"
    })
    void nonOomFailuresIgnored(String reason, int exitCode) {
        WorkloadRef workload = new WorkloadRef("prod", "Deployment", "web", "uid-1");
        ContainerTermination term = new ContainerTermination("app", reason, exitCode, Instant.now(), 1);
        DetectedFailure failure = new DetectedFailure(workload, "web-pod-1", "app", term, Instant.now());

        Optional<String> agentType = router.route(failure);
        assertTrue(agentType.isEmpty(), "Expected non-OOM failure to be ignored for reason=" + reason + " exit=" + exitCode);
    }

    @Test
    void nullFailureHandled() {
        assertTrue(router.route(null).isEmpty());
    }
}
