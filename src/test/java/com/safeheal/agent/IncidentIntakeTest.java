package com.safeheal.agent;

import com.safeheal.incident.Incident;
import com.safeheal.incident.IncidentEvent;
import com.safeheal.incident.IncidentRepository;
import com.safeheal.incident.IncidentState;
import com.safeheal.incident.Transition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class IncidentIntakeTest {

    static class MockIncidentRepository implements IncidentRepository {
        final Map<String, Incident> incidents = new HashMap<>();
        final Map<String, Instant> cooldowns = new HashMap<>();
        boolean simulateDuplicate = false;

        @Override
        public CreateResult create(Incident incident) {
            if (simulateDuplicate) {
                return new CreateResult.Duplicate("existing-id-1");
            }
            incidents.put(incident.id(), incident);
            return new CreateResult.Created(incident);
        }

        @Override
        public Optional<Incident> findById(String id) {
            return Optional.ofNullable(incidents.get(id));
        }

        @Override
        public Incident applyTransition(Incident incident, IncidentEvent event, Transition transition) {
            return incident;
        }

        @Override
        public Optional<Incident> claimNext(String workerId, Duration leaseDuration, Instant now) {
            return Optional.empty();
        }

        @Override
        public void heartbeat(String id, String workerId, Instant now, Duration leaseDuration) {}

        @Override
        public List<Incident> findResumable(Instant now) {
            return List.of();
        }

        @Override
        public Instant cooldownUntil(String namespace, String workloadUid) {
            return cooldowns.get(namespace + "/" + workloadUid);
        }

        @Override
        public int appliedRemediationsSince(Instant since) {
            return 0;
        }
    }

    record RecordedAuditEvent(String incidentId, String eventType, String payload, Instant timestamp) {}

    private MockIncidentRepository repo;
    private List<RecordedAuditEvent> auditEvents;
    private Clock clock;
    private IncidentIntake intake;
    private static final Instant NOW = Instant.parse("2026-10-14T14:00:00Z");

    @BeforeEach
    void setUp() {
        repo = new MockIncidentRepository();
        auditEvents = new ArrayList<>();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        intake = new IncidentIntake(repo, new TriageRouter(), clock,
            (id, type, payload, ts) -> auditEvents.add(new RecordedAuditEvent(id, type, payload, ts)));
    }

    @Test
    @DisplayName("First detected failure creates an incident row in DETECTED state")
    void firstFailureCreatesIncident() {
        WorkloadRef workload = new WorkloadRef("prod", "Deployment", "orders", "orders-uid");
        ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, NOW.minusSeconds(30), 1);
        DetectedFailure failure = new DetectedFailure(workload, "orders-pod-1", "app", term, NOW);

        Optional<Incident> created = intake.intake(failure, "pod-uid-1");

        assertTrue(created.isPresent());
        Incident incident = created.get();
        assertEquals("prod", incident.namespace());
        assertEquals("orders-uid", incident.workloadUid());
        assertEquals("orders", incident.workloadName());
        assertEquals("oom", incident.agentType());
        assertEquals(IncidentState.DETECTED, incident.state());
        assertEquals(1, repo.incidents.size());
    }

    @Test
    @DisplayName("In-memory deduplication intercepts repeated events with same podUid, container, restartCount")
    void inMemoryDeduplicationInterceptsRepeatedEvents() {
        WorkloadRef workload = new WorkloadRef("prod", "Deployment", "orders", "orders-uid");
        ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, NOW.minusSeconds(30), 1);
        DetectedFailure failure = new DetectedFailure(workload, "orders-pod-1", "app", term, NOW);

        // First intake succeeds
        assertTrue(intake.intake(failure, "pod-uid-1").isPresent());
        assertEquals(1, repo.incidents.size());

        // Second intake with exact same (podUid, container, restartCount) is a silent in-memory no-op
        assertFalse(intake.intake(failure, "pod-uid-1").isPresent());
        // Repo was not hit again
        assertEquals(1, repo.incidents.size());
    }

    @Test
    @DisplayName("Duplicate return type from repository is treated as a silent no-op")
    void duplicateFromRepositoryIsSilentNoOp() {
        repo.simulateDuplicate = true;
        WorkloadRef workload = new WorkloadRef("prod", "Deployment", "orders", "orders-uid");
        ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, NOW.minusSeconds(30), 2);
        DetectedFailure failure = new DetectedFailure(workload, "orders-pod-2", "app", term, NOW);

        Optional<Incident> result = intake.intake(failure, "pod-uid-2");

        assertTrue(result.isEmpty());
        assertEquals(0, repo.incidents.size());
    }

    @Test
    @DisplayName("During cooldown: no incident created, exactly one audit event per workload per window")
    void cooldownBlocksIncidentAndEmitsOneAuditEventPerWindow() {
        WorkloadRef workload = new WorkloadRef("prod", "Deployment", "billing", "billing-uid");
        Instant cooldownWindow = NOW.plus(Duration.ofMinutes(30));
        repo.cooldowns.put("prod/billing-uid", cooldownWindow);

        ContainerTermination term1 = new ContainerTermination("app", "OOMKilled", 137, NOW.minusSeconds(10), 1);
        DetectedFailure failure1 = new DetectedFailure(workload, "billing-pod-1", "app", term1, NOW);

        // First attempt inside cooldown
        Optional<Incident> result1 = intake.intake(failure1, "billing-pod-1-uid");
        assertTrue(result1.isEmpty());
        assertEquals(0, repo.incidents.size());
        assertEquals(1, auditEvents.size());
        assertEquals("DETECTION_BLOCKED_COOLDOWN", auditEvents.getFirst().eventType());

        // Second attempt with different pod inside same cooldown window
        ContainerTermination term2 = new ContainerTermination("app", "OOMKilled", 137, NOW.minusSeconds(5), 1);
        DetectedFailure failure2 = new DetectedFailure(workload, "billing-pod-2", "app", term2, NOW);
        Optional<Incident> result2 = intake.intake(failure2, "billing-pod-2-uid");

        assertTrue(result2.isEmpty());
        assertEquals(0, repo.incidents.size());
        // Still exactly one audit event for this window
        assertEquals(1, auditEvents.size());
    }
}
