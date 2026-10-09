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
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FsmTransitionTableTest {

    private final Clock clock = Clock.fixed(Instant.parse("2023-01-01T00:00:00Z"), ZoneId.of("UTC"));
    private final FsmConfig config = new FsmConfig(3, 2, Duration.ofMinutes(30));

    private Incident createIncident(IncidentState state, int attempt, int transientRetries) {
        return new Incident("test-id", "default", "uid-1", "test-pod", "test-agent", state, attempt, transientRetries, 1, clock.instant(), clock.instant(), null);
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
            new IncidentEvent.Approved("pid", "hash", "user", Instant.parse("2024-01-01T00:00:00Z")),
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
    void allPairsTest(IncidentState state, IncidentEvent event) {
        Incident incident = createIncident(state, 1, 0);
        if (state.isTerminal()) {
            assertThrows(IllegalTransitionException.class, () -> FsmTransitionTable.decide(incident, event, config, clock));
            return;
        }
        
        try {
            Transition t = FsmTransitionTable.decide(incident, event, config, clock);
            assertNotNull(t.nextState());
            assertNotNull(t.sideEffect());
        } catch (IllegalTransitionException e) {
            // expected for invalid transitions
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
            new IncidentEvent.Approved("pid", "hash", "user", Instant.parse("2024-01-01T00:00:00Z")),
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
            while (!incident.state().isTerminal()) {
                IncidentEvent evt = allEvents[rand.nextInt(allEvents.length)];
                try {
                    Transition t = FsmTransitionTable.decide(incident, evt, config, clock);
                    int newAttempt = incident.attempt();
                    int newTransient = incident.transientRetries();
                    if (t.sideEffect() == Transition.SideEffect.INCREMENT_ATTEMPT) newAttempt++;
                    if (t.sideEffect() == Transition.SideEffect.INCREMENT_TRANSIENT_RETRY) newTransient++;
                    
                    incident = new Incident(incident.id(), incident.namespace(), incident.workloadUid(), incident.workloadName(), incident.agentType(), t.nextState(), newAttempt, newTransient, incident.version() + 1, incident.createdAt(), clock.instant(), incident.closedAt());
                } catch (IllegalTransitionException e) {
                    // skip illegal events
                }
            }
            assertTrue(incident.state().isTerminal());
            assertTrue(incident.attempt() <= config.maxAttempts() + 1); // some transitions maxAttempt
            assertTrue(incident.transientRetries() <= config.maxTransientRetries() + 1);
        }
    }

    @Test
    void expiredApprovalRejected() {
        Incident incident = createIncident(IncidentState.AWAITING_APPROVAL, 1, 0);
        IncidentEvent.Approved expired = new IncidentEvent.Approved("pid", "hash", "user", clock.instant().minusSeconds(1));
        assertThrows(IllegalTransitionException.class, () -> FsmTransitionTable.decide(incident, expired, config, clock));
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
}
