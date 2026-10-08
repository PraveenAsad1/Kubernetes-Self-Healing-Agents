package com.safeheal.incident;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class FsmTransitionTableTest {

    private final Clock clock = Clock.fixed(Instant.parse("2023-01-01T00:00:00Z"), ZoneId.of("UTC"));
    private final FsmConfig config = new FsmConfig(3, 2, Duration.ofMinutes(30));

    private Incident createIncident(IncidentState state, int attempt, int transientRetries) {
        return new Incident("test-id", "default", "uid-1", "test-pod", "test-agent", state, attempt, transientRetries, 1, clock.instant(), clock.instant(), null);
    }

    @Test
    void validTransition() {
        Incident incident = createIncident(IncidentState.DETECTED, 1, 0);
        Transition t = FsmTransitionTable.decide(incident, new IncidentEvent.ProcessingStarted(), config, clock);
        assertEquals(IncidentState.INVESTIGATING, t.nextState());
    }

    @Test
    void invalidTransitionThrows() {
        Incident incident = createIncident(IncidentState.DETECTED, 1, 0);
        assertThrows(IllegalTransitionException.class, () -> 
            FsmTransitionTable.decide(incident, new IncidentEvent.EvidenceGathered(), config, clock)
        );
    }

    @Test
    void terminalStateAcceptsNoEvents() {
        Incident incident = createIncident(IncidentState.RESOLVED, 1, 0);
        assertThrows(IllegalTransitionException.class, () -> 
            FsmTransitionTable.decide(incident, new IncidentEvent.ProcessingStarted(), config, clock)
        );
    }

    @Test
    void transientFailureBelowLimit() {
        Incident incident = createIncident(IncidentState.INVESTIGATING, 1, 0);
        Transition t = FsmTransitionTable.decide(incident, new IncidentEvent.K8sReadFailed(), config, clock);
        assertEquals(IncidentState.INVESTIGATING, t.nextState());
        assertEquals(Transition.SideEffect.INCREMENT_TRANSIENT_RETRY, t.sideEffect());
    }

    @Test
    void transientFailureAboveLimit() {
        Incident incident = createIncident(IncidentState.INVESTIGATING, 1, 3);
        Transition t = FsmTransitionTable.decide(incident, new IncidentEvent.K8sReadFailed(), config, clock);
        assertEquals(IncidentState.ESCALATED, t.nextState());
    }
}
