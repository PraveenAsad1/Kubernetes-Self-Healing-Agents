package com.safeheal.incident;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FsmTransitionTableTest {

    private final Clock clock = Clock.fixed(Instant.parse("2023-01-01T00:00:00Z"), ZoneId.of("UTC"));
    private final FsmConfig config = new FsmConfig(3, 2, Duration.ofMinutes(30));

    private Incident createIncident(IncidentState state, int attempt, int transientRetries) {
        return new Incident("test-id", "default", "uid-1", "test-pod", "test-agent", state, attempt, transientRetries, 1, null, null, clock.instant(), clock.instant(), state.isTerminal() ? clock.instant() : null);
    }

    // Golden table from docs/ARCHITECTURE.md
    record Expected(IncidentState nextState, Set<Transition.SideEffect> sideEffects) {}
    
    private static Map<IncidentState, Map<Class<?>, Expected>> GOLDEN_TABLE = new HashMap<>();
    
    static {
        for (IncidentState s : IncidentState.values()) GOLDEN_TABLE.put(s, new HashMap<>());
        
        // DETECTED
        GOLDEN_TABLE.get(IncidentState.DETECTED).put(IncidentEvent.ProcessingStarted.class, new Expected(IncidentState.INVESTIGATING, EnumSet.of(Transition.SideEffect.LOG)));
        GOLDEN_TABLE.get(IncidentState.DETECTED).put(IncidentEvent.WorkloadDeleted.class, new Expected(IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.LOG)));
        GOLDEN_TABLE.get(IncidentState.DETECTED).put(IncidentEvent.Recurrence.class, new Expected(IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG)));

        // INVESTIGATING
        GOLDEN_TABLE.get(IncidentState.INVESTIGATING).put(IncidentEvent.EvidenceGathered.class, new Expected(IncidentState.DIAGNOSING, EnumSet.of(Transition.SideEffect.STORE_EVIDENCE, Transition.SideEffect.RESET_TRANSIENT_RETRIES)));
        GOLDEN_TABLE.get(IncidentState.INVESTIGATING).put(IncidentEvent.K8sReadFailed.class, new Expected(IncidentState.INVESTIGATING, EnumSet.of(Transition.SideEffect.INCREMENT_TRANSIENT_RETRY))); // default case
        GOLDEN_TABLE.get(IncidentState.INVESTIGATING).put(IncidentEvent.Timeout.class, new Expected(IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG)));
        GOLDEN_TABLE.get(IncidentState.INVESTIGATING).put(IncidentEvent.WorkloadDeleted.class, new Expected(IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.LOG)));

        // DIAGNOSING
        GOLDEN_TABLE.get(IncidentState.DIAGNOSING).put(IncidentEvent.ProposalAccepted.class, new Expected(IncidentState.AWAITING_APPROVAL, EnumSet.of(Transition.SideEffect.STORE_PROPOSAL, Transition.SideEffect.RESET_TRANSIENT_RETRIES)));
        GOLDEN_TABLE.get(IncidentState.DIAGNOSING).put(IncidentEvent.LlmFailed.class, new Expected(IncidentState.DIAGNOSING, EnumSet.of(Transition.SideEffect.INCREMENT_TRANSIENT_RETRY)));
        GOLDEN_TABLE.get(IncidentState.DIAGNOSING).put(IncidentEvent.LlmInvalid.class, new Expected(IncidentState.DIAGNOSING, EnumSet.of(Transition.SideEffect.INCREMENT_TRANSIENT_RETRY)));
        GOLDEN_TABLE.get(IncidentState.DIAGNOSING).put(IncidentEvent.PolicyDenied.class, new Expected(IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG)));
        GOLDEN_TABLE.get(IncidentState.DIAGNOSING).put(IncidentEvent.Timeout.class, new Expected(IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG)));
        GOLDEN_TABLE.get(IncidentState.DIAGNOSING).put(IncidentEvent.WorkloadDeleted.class, new Expected(IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.LOG)));

        // AWAITING_APPROVAL
        GOLDEN_TABLE.get(IncidentState.AWAITING_APPROVAL).put(IncidentEvent.Approved.class, new Expected(IncidentState.REMEDIATING, EnumSet.of(Transition.SideEffect.CREATE_INTENT, Transition.SideEffect.RESET_TRANSIENT_RETRIES)));
        GOLDEN_TABLE.get(IncidentState.AWAITING_APPROVAL).put(IncidentEvent.Rejected.class, new Expected(IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG)));
        GOLDEN_TABLE.get(IncidentState.AWAITING_APPROVAL).put(IncidentEvent.Timeout.class, new Expected(IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG)));
        GOLDEN_TABLE.get(IncidentState.AWAITING_APPROVAL).put(IncidentEvent.WorkloadDeleted.class, new Expected(IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.LOG)));

        // REMEDIATING
        GOLDEN_TABLE.get(IncidentState.REMEDIATING).put(IncidentEvent.ExecutionPolicyDenied.class, new Expected(IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.TERMINATE_INTENT)));
        GOLDEN_TABLE.get(IncidentState.REMEDIATING).put(IncidentEvent.Conflict.class, new Expected(IncidentState.INVESTIGATING, EnumSet.of(Transition.SideEffect.INCREMENT_ATTEMPT, Transition.SideEffect.TERMINATE_INTENT, Transition.SideEffect.RESET_TRANSIENT_RETRIES)));
        GOLDEN_TABLE.get(IncidentState.REMEDIATING).put(IncidentEvent.PatchFailed.class, new Expected(IncidentState.REMEDIATING, EnumSet.of(Transition.SideEffect.INCREMENT_TRANSIENT_RETRY)));
        GOLDEN_TABLE.get(IncidentState.REMEDIATING).put(IncidentEvent.Applied.class, new Expected(IncidentState.VERIFYING, EnumSet.of(Transition.SideEffect.MARK_INTENT_APPLIED, Transition.SideEffect.RESET_TRANSIENT_RETRIES)));
        GOLDEN_TABLE.get(IncidentState.REMEDIATING).put(IncidentEvent.ReconcileResume.class, new Expected(IncidentState.VERIFYING, EnumSet.of(Transition.SideEffect.SYNC_INTENT_STATE)));
        GOLDEN_TABLE.get(IncidentState.REMEDIATING).put(IncidentEvent.Timeout.class, new Expected(IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.TERMINATE_INTENT)));
        GOLDEN_TABLE.get(IncidentState.REMEDIATING).put(IncidentEvent.WorkloadDeleted.class, new Expected(IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.TERMINATE_INTENT)));

        // VERIFYING
        GOLDEN_TABLE.get(IncidentState.VERIFYING).put(IncidentEvent.Verified.class, new Expected(IncidentState.RESOLVED, EnumSet.of(Transition.SideEffect.LOG)));
        GOLDEN_TABLE.get(IncidentState.VERIFYING).put(IncidentEvent.VerificationFailed.class, new Expected(IncidentState.REFLECTING, EnumSet.of(Transition.SideEffect.LOG)));
        GOLDEN_TABLE.get(IncidentState.VERIFYING).put(IncidentEvent.Timeout.class, new Expected(IncidentState.REFLECTING, EnumSet.of(Transition.SideEffect.LOG)));
        GOLDEN_TABLE.get(IncidentState.VERIFYING).put(IncidentEvent.WorkloadDeleted.class, new Expected(IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.LOG)));
        GOLDEN_TABLE.get(IncidentState.VERIFYING).put(IncidentEvent.Recurrence.class, new Expected(IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG)));

        // REFLECTING
        GOLDEN_TABLE.get(IncidentState.REFLECTING).put(IncidentEvent.ReflectionCompleted.class, new Expected(IncidentState.INVESTIGATING, EnumSet.of(Transition.SideEffect.INCREMENT_ATTEMPT, Transition.SideEffect.RESET_TRANSIENT_RETRIES)));
    }

    static Stream<Arguments> stateEventMatrix() {
        List<Arguments> args = new ArrayList<>();
        IncidentEvent[] allEvents = new IncidentEvent[] {
            new IncidentEvent.ProcessingStarted(),
            new IncidentEvent.EvidenceGathered(),
            new IncidentEvent.K8sReadFailed(),
            new IncidentEvent.LlmInvalid(),
            new IncidentEvent.LlmFailed(),
            new IncidentEvent.PolicyDenied(),
            new IncidentEvent.ExecutionPolicyDenied(),
            new IncidentEvent.ProposalAccepted(),
            new IncidentEvent.Approved("pid", "hash", "user", Instant.parse("2023-01-01T00:00:00Z")),
            new IncidentEvent.Rejected(),
            new IncidentEvent.Timeout(),
            new IncidentEvent.WorkloadDeleted(),
            new IncidentEvent.Conflict(),
            new IncidentEvent.Applied(),
            new IncidentEvent.PatchFailed(),
            new IncidentEvent.Verified(),
            new IncidentEvent.VerificationFailed(),
            new IncidentEvent.ReflectionCompleted(),
            new IncidentEvent.ReconcileResume(),
            new IncidentEvent.Recurrence()
        };
        for (IncidentState state : IncidentState.values()) {
            for (IncidentEvent event : allEvents) {
                args.add(Arguments.of(state, event));
            }
        }
        return args.stream();
    }

    @ParameterizedTest
    @MethodSource("stateEventMatrix")
    void matrixGoldenTest(IncidentState state, IncidentEvent event) {
        Incident incident = createIncident(state, 1, 0); // below limits, valid approval
        Expected expected = GOLDEN_TABLE.get(state).get(event.getClass());
        if (expected == null) {
            assertThrows(IllegalTransitionException.class, () -> FsmTransitionTable.decide(incident, event, config, clock));
        } else {
            Transition t = FsmTransitionTable.decide(incident, event, config, clock);
            assertEquals(expected.nextState(), t.nextState());
            assertEquals(expected.sideEffects(), t.sideEffects());
        }
    }

    @Test
    void propertyStyleTest() {
        Random rand = new Random(42);
        IncidentEvent[] allEvents = new IncidentEvent[] {
            new IncidentEvent.ProcessingStarted(),
            new IncidentEvent.EvidenceGathered(),
            new IncidentEvent.K8sReadFailed(),
            new IncidentEvent.LlmInvalid(),
            new IncidentEvent.LlmFailed(),
            new IncidentEvent.PolicyDenied(),
            new IncidentEvent.ExecutionPolicyDenied(),
            new IncidentEvent.ProposalAccepted(),
            new IncidentEvent.Approved("pid", "hash", "user", Instant.parse("2023-01-01T00:00:00Z")),
            new IncidentEvent.Rejected(),
            new IncidentEvent.Timeout(),
            new IncidentEvent.WorkloadDeleted(),
            new IncidentEvent.Conflict(),
            new IncidentEvent.Applied(),
            new IncidentEvent.PatchFailed(),
            new IncidentEvent.Verified(),
            new IncidentEvent.VerificationFailed(),
            new IncidentEvent.ReflectionCompleted(),
            new IncidentEvent.ReconcileResume(),
            new IncidentEvent.Recurrence()
        };
        
        for (int i = 0; i < 100; i++) {
            Incident incident = createIncident(IncidentState.DETECTED, 1, 0);
            int sanityLimit = 1000;
            while (!incident.state().isTerminal() && sanityLimit-- > 0) {
                IncidentEvent evt = allEvents[rand.nextInt(allEvents.length)];
                try {
                    Transition t = FsmTransitionTable.decide(incident, evt, config, clock);
                    int newAttempt = incident.attempt();
                    int newTransient = incident.transientRetries();
                    if (t.sideEffects().contains(Transition.SideEffect.INCREMENT_ATTEMPT)) newAttempt++;
                    if (t.sideEffects().contains(Transition.SideEffect.INCREMENT_TRANSIENT_RETRY)) newTransient++;
                    if (t.sideEffects().contains(Transition.SideEffect.RESET_TRANSIENT_RETRIES)) newTransient = 0;
                    
                    incident = new Incident(incident.id(), incident.namespace(), incident.workloadUid(), incident.workloadName(), incident.agentType(), t.nextState(), newAttempt, newTransient, incident.version() + 1, incident.claimedBy(), incident.leaseUntil(), incident.createdAt(), clock.instant(), incident.state().isTerminal() ? clock.instant() : null);
                } catch (IllegalTransitionException e) {
                    // expected
                }
            }
            assertTrue(incident.state().isTerminal());
            assertTrue(incident.attempt() <= config.maxAttempts(), "Attempt exceeded limit: " + incident.attempt());
            assertTrue(incident.transientRetries() <= config.maxTransientRetries(), "Transient retries exceeded limit: " + incident.transientRetries());
        }
    }

    @Test
    void expiredApprovalRejected() {
        Incident incident = createIncident(IncidentState.AWAITING_APPROVAL, 1, 0);
        IncidentEvent.Approved expired = new IncidentEvent.Approved("pid", "hash", "user", clock.instant().minus(config.approvalTtl()).minusSeconds(1));
        assertThrows(IllegalTransitionException.class, () -> FsmTransitionTable.decide(incident, expired, config, clock));
    }
    
    @Test
    void approvalJustValid() {
        Incident incident = createIncident(IncidentState.AWAITING_APPROVAL, 1, 0);
        IncidentEvent.Approved valid = new IncidentEvent.Approved("pid", "hash", "user", clock.instant().minus(config.approvalTtl()));
        Transition t = FsmTransitionTable.decide(incident, valid, config, clock);
        assertEquals(IncidentState.REMEDIATING, t.nextState());
    }

    @Test
    void k8sReadFailedLimits() {
        Incident incident = createIncident(IncidentState.INVESTIGATING, 1, config.maxTransientRetries() - 1);
        Transition t1 = FsmTransitionTable.decide(incident, new IncidentEvent.K8sReadFailed(), config, clock);
        assertEquals(IncidentState.INVESTIGATING, t1.nextState());
        
        Incident limitIncident = createIncident(IncidentState.INVESTIGATING, 1, config.maxTransientRetries());
        Transition t2 = FsmTransitionTable.decide(limitIncident, new IncidentEvent.K8sReadFailed(), config, clock);
        assertEquals(IncidentState.ESCALATED, t2.nextState());
    }
    
    @Test
    void conflictLimits() {
        Incident incident = createIncident(IncidentState.REMEDIATING, config.maxAttempts() - 1, 0);
        Transition t1 = FsmTransitionTable.decide(incident, new IncidentEvent.Conflict(), config, clock);
        assertEquals(IncidentState.INVESTIGATING, t1.nextState());
        
        Incident limitIncident = createIncident(IncidentState.REMEDIATING, config.maxAttempts(), 0);
        Transition t2 = FsmTransitionTable.decide(limitIncident, new IncidentEvent.Conflict(), config, clock);
        assertEquals(IncidentState.ESCALATED, t2.nextState());
    }
}
