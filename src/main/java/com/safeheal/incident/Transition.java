package com.safeheal.incident;

public record Transition(
    IncidentState nextState,
    SideEffect sideEffect
) {
    public enum SideEffect {
        NONE,
        CREATE_INTENT,
        INCREMENT_ATTEMPT,
        CLOSE_INCIDENT,
        STORE_EVIDENCE,
        STORE_PROPOSAL,
        MARK_INTENT_APPLIED,
        TERMINATE_INTENT,
        SYNC_INTENT_STATE,
        INCREMENT_TRANSIENT_RETRY,
        LOG
    }
}
