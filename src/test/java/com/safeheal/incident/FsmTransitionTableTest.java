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

    record Expected(IncidentState nextState, Set<Transition.SideEffect> sideEffects) {}
    
    private static Map<IncidentState, Map<Class<?>, Expected>> GOLDEN_BELOW = new HashMap<>();
    private static Map<IncidentState, Map<Class<?>, Expected>> GOLDEN_AT = new HashMap<>();
    
    static {
        for (IncidentState s : IncidentState.values()) {
            GOLDEN_BELOW.put(s, new HashMap<>());
            GOLDEN_AT.put(s, new HashMap<>());
        }
        
        // DETECTED
        addG(IncidentState.DETECTED, IncidentEvent.ProcessingStarted.class, IncidentState.INVESTIGATING, EnumSet.of(Transition.SideEffect.LOG));
        addG(IncidentState.DETECTED, IncidentEvent.WorkloadDeleted.class, IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));
        addG(IncidentState.DETECTED, IncidentEvent.Recurrence.class, IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));
        addG(IncidentState.DETECTED, IncidentEvent.Timeout.class, IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));

        // INVESTIGATING
        addG(IncidentState.INVESTIGATING, IncidentEvent.EvidenceGathered.class, IncidentState.DIAGNOSING, EnumSet.of(Transition.SideEffect.STORE_EVIDENCE, Transition.SideEffect.RESET_TRANSIENT_RETRIES));
        addGBelow(IncidentState.INVESTIGATING, IncidentEvent.K8sReadFailed.class, IncidentState.INVESTIGATING, EnumSet.of(Transition.SideEffect.INCREMENT_TRANSIENT_RETRY));
        addGAt(IncidentState.INVESTIGATING, IncidentEvent.K8sReadFailed.class, IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));
        addG(IncidentState.INVESTIGATING, IncidentEvent.Timeout.class, IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));
        addG(IncidentState.INVESTIGATING, IncidentEvent.WorkloadDeleted.class, IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));

        // DIAGNOSING
        addG(IncidentState.DIAGNOSING, IncidentEvent.ProposalAccepted.class, IncidentState.AWAITING_APPROVAL, EnumSet.of(Transition.SideEffect.STORE_PROPOSAL, Transition.SideEffect.RESET_TRANSIENT_RETRIES));
        addGBelow(IncidentState.DIAGNOSING, IncidentEvent.LlmFailed.class, IncidentState.DIAGNOSING, EnumSet.of(Transition.SideEffect.INCREMENT_TRANSIENT_RETRY));
        addGAt(IncidentState.DIAGNOSING, IncidentEvent.LlmFailed.class, IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));
        addGBelow(IncidentState.DIAGNOSING, IncidentEvent.LlmInvalid.class, IncidentState.DIAGNOSING, EnumSet.of(Transition.SideEffect.INCREMENT_TRANSIENT_RETRY));
        addGAt(IncidentState.DIAGNOSING, IncidentEvent.LlmInvalid.class, IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));
        addG(IncidentState.DIAGNOSING, IncidentEvent.PolicyDenied.class, IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));
        addG(IncidentState.DIAGNOSING, IncidentEvent.Timeout.class, IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));
        addG(IncidentState.DIAGNOSING, IncidentEvent.WorkloadDeleted.class, IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));

        // AWAITING_APPROVAL
        addGBelow(IncidentState.AWAITING_APPROVAL, IncidentEvent.Approved.class, IncidentState.REMEDIATING, EnumSet.of(Transition.SideEffect.CREATE_INTENT, Transition.SideEffect.RESET_TRANSIENT_RETRIES));
        addG(IncidentState.AWAITING_APPROVAL, IncidentEvent.Rejected.class, IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));
        addG(IncidentState.AWAITING_APPROVAL, IncidentEvent.Timeout.class, IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));
        addG(IncidentState.AWAITING_APPROVAL, IncidentEvent.WorkloadDeleted.class, IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));

        // REMEDIATING
        addG(IncidentState.REMEDIATING, IncidentEvent.ExecutionPolicyDenied.class, IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.TERMINATE_INTENT, Transition.SideEffect.CLOSE_INCIDENT));
        addGBelow(IncidentState.REMEDIATING, IncidentEvent.Conflict.class, IncidentState.INVESTIGATING, EnumSet.of(Transition.SideEffect.INCREMENT_ATTEMPT, Transition.SideEffect.TERMINATE_INTENT, Transition.SideEffect.RESET_TRANSIENT_RETRIES, Transition.SideEffect.VOID_APPROVAL));
        addGAt(IncidentState.REMEDIATING, IncidentEvent.Conflict.class, IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.TERMINATE_INTENT, Transition.SideEffect.VOID_APPROVAL, Transition.SideEffect.CLOSE_INCIDENT));
        addGBelow(IncidentState.REMEDIATING, IncidentEvent.PatchFailed.class, IncidentState.REMEDIATING, EnumSet.of(Transition.SideEffect.INCREMENT_TRANSIENT_RETRY));
        addGAt(IncidentState.REMEDIATING, IncidentEvent.PatchFailed.class, IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.TERMINATE_INTENT, Transition.SideEffect.CLOSE_INCIDENT));
        addG(IncidentState.REMEDIATING, IncidentEvent.Applied.class, IncidentState.VERIFYING, EnumSet.of(Transition.SideEffect.MARK_INTENT_APPLIED, Transition.SideEffect.RESET_TRANSIENT_RETRIES));
        addG(IncidentState.REMEDIATING, IncidentEvent.ReconcileResume.class, IncidentState.VERIFYING, EnumSet.of(Transition.SideEffect.SYNC_INTENT_STATE));
        addG(IncidentState.REMEDIATING, IncidentEvent.Timeout.class, IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.TERMINATE_INTENT, Transition.SideEffect.CLOSE_INCIDENT));
        addG(IncidentState.REMEDIATING, IncidentEvent.WorkloadDeleted.class, IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.TERMINATE_INTENT, Transition.SideEffect.CLOSE_INCIDENT));

        // VERIFYING
        addG(IncidentState.VERIFYING, IncidentEvent.Verified.class, IncidentState.RESOLVED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));
        addG(IncidentState.VERIFYING, IncidentEvent.VerificationFailed.class, IncidentState.REFLECTING, EnumSet.of(Transition.SideEffect.LOG));
        addG(IncidentState.VERIFYING, IncidentEvent.Timeout.class, IncidentState.REFLECTING, EnumSet.of(Transition.SideEffect.LOG));
        addG(IncidentState.VERIFYING, IncidentEvent.WorkloadDeleted.class, IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));
        addG(IncidentState.VERIFYING, IncidentEvent.Recurrence.class, IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));

        // REFLECTING
        addGBelow(IncidentState.REFLECTING, IncidentEvent.ReflectionCompleted.class, IncidentState.INVESTIGATING, EnumSet.of(Transition.SideEffect.INCREMENT_ATTEMPT, Transition.SideEffect.RESET_TRANSIENT_RETRIES));
        addGAt(IncidentState.REFLECTING, IncidentEvent.ReflectionCompleted.class, IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));
        addG(IncidentState.REFLECTING, IncidentEvent.WorkloadDeleted.class, IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));
        addG(IncidentState.REFLECTING, IncidentEvent.Timeout.class, IncidentState.ESCALATED, EnumSet.of(Transition.SideEffect.LOG, Transition.SideEffect.CLOSE_INCIDENT));
    }

    private static void addG(IncidentState state, Class<?> clazz, IncidentState to, Set<Transition.SideEffect> effects) {
        addGBelow(state, clazz, to, effects);
        addGAt(state, clazz, to, effects);
    }
    private static void addGBelow(IncidentState state, Class<?> clazz, IncidentState to, Set<Transition.SideEffect> effects) {
        GOLDEN_BELOW.get(state).put(clazz, new Expected(to, effects));
    }
    private static void addGAt(IncidentState state, Class<?> clazz, IncidentState to, Set<Transition.SideEffect> effects) {
        GOLDEN_AT.get(state).put(clazz, new Expected(to, effects));
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
                args.add(Arguments.of(state, event, false)); // below limit
                args.add(Arguments.of(state, event, true));  // at limit
            }
        }
        return args.stream();
    }

    @ParameterizedTest
    @MethodSource("stateEventMatrix")
    void matrixGoldenTest(IncidentState state, IncidentEvent event, boolean atLimit) {
        int attempt = atLimit ? config.maxAttempts() : 1;
        int transientRetries = atLimit ? config.maxTransientRetries() : 0;
        
        Incident incident = createIncident(state, attempt, transientRetries);
        Expected expected = atLimit ? GOLDEN_AT.get(state).get(event.getClass()) : GOLDEN_BELOW.get(state).get(event.getClass());
        
        IncidentEvent finalEvent = event;
        if (event instanceof IncidentEvent.Approved && atLimit) {
            expected = null; // simulate expired approval for atLimit
            finalEvent = new IncidentEvent.Approved("pid", "hash", "user", clock.instant().minus(config.approvalTtl()).minusSeconds(1));
        }
        
        IncidentEvent eventToTest = finalEvent;
        if (expected == null) {
            assertThrows(IllegalTransitionException.class, () -> FsmTransitionTable.decide(incident, eventToTest, config, clock));
        } else {
            Transition t = FsmTransitionTable.decide(incident, eventToTest, config, clock);
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
            assertEquals(incident.attempt() <= config.maxAttempts(), true, "Attempt exceeded limit: " + incident.attempt());
            assertEquals(incident.transientRetries() <= config.maxTransientRetries(), true, "Transient retries exceeded limit: " + incident.transientRetries());
        }
    }

    @Test
    void testRuleImmutability() {
        assertThrows(UnsupportedOperationException.class, () -> FsmTransitionTable.getRules().add(null));
        if (!FsmTransitionTable.getRules().isEmpty()) {
            assertThrows(UnsupportedOperationException.class, () -> FsmTransitionTable.getRules().get(0).sideEffects().add(Transition.SideEffect.LOG));
        }
    }
    
    @Test
    void testApprovalTtl() {
        Incident i = createIncident(IncidentState.AWAITING_APPROVAL, 1, 0);
        
        // Exact TTL: should be accepted
        IncidentEvent exactTtlEvent = new IncidentEvent.Approved("pid", "hash", "user", clock.instant().minus(config.approvalTtl()));
        assertDoesNotThrow(() -> FsmTransitionTable.decide(i, exactTtlEvent, config, clock));
        
        // TTL + 1s: should be rejected
        IncidentEvent expiredEvent = new IncidentEvent.Approved("pid", "hash", "user", clock.instant().minus(config.approvalTtl()).minusSeconds(1));
        assertThrows(IllegalTransitionException.class, () -> FsmTransitionTable.decide(i, expiredEvent, config, clock));
    }

    @Test
    void testConfigValidation() {
        assertThrows(IllegalArgumentException.class, () -> new FsmConfig(0, 2, Duration.ofMinutes(30)));
        assertThrows(IllegalArgumentException.class, () -> new FsmConfig(3, -1, Duration.ofMinutes(30)));
        assertThrows(IllegalArgumentException.class, () -> new FsmConfig(3, 2, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new FsmConfig(3, 2, Duration.ofMinutes(-1)));
    }
}
