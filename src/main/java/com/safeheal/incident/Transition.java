package com.safeheal.incident;

import java.util.Set;

public record Transition(IncidentState nextState, Set<SideEffect> sideEffects) {
    public enum SideEffect {
        LOG, STORE_EVIDENCE, INCREMENT_TRANSIENT_RETRY, STORE_PROPOSAL, CREATE_INTENT, TERMINATE_INTENT, INCREMENT_ATTEMPT, MARK_INTENT_APPLIED, SYNC_INTENT_STATE, RESET_TRANSIENT_RETRIES
    }
}
