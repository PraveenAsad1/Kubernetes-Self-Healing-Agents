package com.safeheal.incident;

public enum IncidentState {
    DETECTED,
    INVESTIGATING,
    DIAGNOSING,
    AWAITING_APPROVAL,
    REMEDIATING,
    VERIFYING,
    REFLECTING,
    RESOLVED,
    ESCALATED,
    CANCELLED;

    public boolean isTerminal() {
        return this == RESOLVED || this == ESCALATED || this == CANCELLED;
    }
}
