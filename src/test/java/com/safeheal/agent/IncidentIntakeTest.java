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
        boolean failNextCreate = false;

        @Override
        public CreateResult create(Incident incident) {
            if (failNextCreate) {
                failNextCreate = false;
                throw new RuntimeException("Simulated transient DB outage");
            }
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

    static class MutableClock extends Clock {
        private Instant current;
        private final ZoneOffset zone = ZoneOffset.UTC;

        MutableClock(Instant initial) {
            this.current = initial;
        }

        void set(Instant next) {
            this.current = next;
        }

        @Override
        public ZoneOffset getZone() {
            return zone;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    record RecordedAuditEvent(String incidentId, String eventType, String payload, Instant timestamp) {}

    private MockIncidentRepository repo;
    private List<RecordedAuditEvent> auditEvents;
    private MutableClock clock;
    private IncidentIntake intake;
    private static final Instant NOW = Instant.parse("2026-10-14T14:00:00Z");

    @BeforeEach
    void setUp() {
        repo = new MockIncidentRepository();
        auditEvents = new ArrayList<>();
        clock = new MutableClock(NOW);
        intake = new IncidentIntake(repo, new TriageRouter(), clock,
            (id, type, payload, ts) -> auditEvents.add(new RecordedAuditEvent(id, type, payload, ts)));
    }

    @Test
    @DisplayName("First detected failure creates an incident row in DETECTED state")
    void firstFailureCreatesIncident() {
        WorkloadRef workload = new WorkloadRef("prod", "Deployment", "orders", "orders-uid");
        ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, NOW.minusSeconds(30), 1);
        DetectedFailure failure = new DetectedFailure(workload, "orders-pod-1", "pod-uid-1", "app", term, NOW);

        Optional<Incident> created = intake.intake(failure);

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
        DetectedFailure failure = new DetectedFailure(workload, "orders-pod-1", "pod-uid-1", "app", term, NOW);

        // First intake succeeds
        assertTrue(intake.intake(failure).isPresent());
        assertEquals(1, repo.incidents.size());

        // Second intake with exact same (podUid, container, restartCount) is a silent in-memory no-op
        assertFalse(intake.intake(failure).isPresent());
        assertEquals(1, repo.incidents.size());
    }

    @Test
    @DisplayName("Duplicate return type from repository is treated as a silent no-op and records dedupeKey")
    void duplicateFromRepositoryIsSilentNoOp() {
        repo.simulateDuplicate = true;
        WorkloadRef workload = new WorkloadRef("prod", "Deployment", "orders", "orders-uid");
        ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, NOW.minusSeconds(30), 2);
        DetectedFailure failure = new DetectedFailure(workload, "orders-pod-2", "pod-uid-2", "app", term, NOW);

        Optional<Incident> result = intake.intake(failure);

        assertTrue(result.isEmpty());
        assertEquals(0, repo.incidents.size());

        // Second call is caught in-memory without reaching repository
        repo.simulateDuplicate = false;
        assertTrue(intake.intake(failure).isEmpty());
        assertEquals(0, repo.incidents.size());
    }

    @Test
    @DisplayName("Repository throws on first call, second call with the same termination creates the incident")
    void repositoryThrowsOnFirstCallSecondCallSucceeds() {
        repo.failNextCreate = true;
        WorkloadRef workload = new WorkloadRef("prod", "Deployment", "orders", "orders-uid");
        ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, NOW.minusSeconds(30), 1);
        DetectedFailure failure = new DetectedFailure(workload, "orders-pod-1", "pod-uid-1", "app", term, NOW);

        // First call fails and rethrows; key must be removed from dedupe structure
        assertThrows(RuntimeException.class, () -> intake.intake(failure));
        assertEquals(0, repo.incidents.size());

        // Second call with same termination succeeds and creates incident
        Optional<Incident> created = intake.intake(failure);
        assertTrue(created.isPresent());
        assertEquals(1, repo.incidents.size());
    }

    @Test
    @DisplayName("Cooldown-blocked termination is NOT recorded in dedupe and is processed after cooldown expires")
    void cooldownBlockedTerminationProcessedAfterExpiry() {
        WorkloadRef workload = new WorkloadRef("prod", "Deployment", "billing", "billing-uid");
        Instant cooldownUntil = NOW.plus(Duration.ofMinutes(10));
        repo.cooldowns.put("prod/billing-uid", cooldownUntil);

        ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, NOW.minusSeconds(10), 1);
        DetectedFailure failure = new DetectedFailure(workload, "billing-pod-1", "billing-pod-1-uid", "app", term, NOW);

        // 1. First attempt during cooldown: blocked, audit event emitted, NO incident row, NO dedupe recorded
        Optional<Incident> result1 = intake.intake(failure);
        assertTrue(result1.isEmpty());
        assertEquals(0, repo.incidents.size());
        assertEquals(1, auditEvents.size());
        assertEquals("DETECTION_BLOCKED_COOLDOWN", auditEvents.getFirst().eventType());

        // 2. Advance time past cooldown
        clock.set(NOW.plus(Duration.ofMinutes(11)));

        // 3. Exact same termination is processed successfully after cooldown expires
        Optional<Incident> result2 = intake.intake(failure);
        assertTrue(result2.isPresent());
        assertEquals(1, repo.incidents.size());
    }

    @Test
    @DisplayName("Dedupe structure stays within its bound after 20_000 distinct keys")
    void dedupeStructureStaysWithinBoundsAfterManyKeys() {
        WorkloadRef workload = new WorkloadRef("prod", "Deployment", "bulk-service", "bulk-uid");
        repo.simulateDuplicate = true; // So we don't store 20,000 incident objects in repo memory

        for (int i = 0; i < 20_000; i++) {
            ContainerTermination term = new ContainerTermination("app", "OOMKilled", 137, NOW.minusSeconds(10), i);
            DetectedFailure failure = new DetectedFailure(workload, "bulk-pod-" + i, "bulk-uid-" + i, "app", term, NOW);
            intake.intake(failure);
        }

        assertTrue(intake.dedupeCacheSize() <= 10_000, "Expected dedupe cache size <= 10,000 but was: " + intake.dedupeCacheSize());
    }
}
