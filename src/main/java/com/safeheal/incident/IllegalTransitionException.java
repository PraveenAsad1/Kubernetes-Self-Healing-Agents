package com.safeheal.incident;

public class IllegalTransitionException extends RuntimeException {
    public IllegalTransitionException(IncidentState state, IncidentEvent event, String reason) {
        super(String.format("Illegal transition from state %s on event %s: %s", state, event.getClass().getSimpleName(), reason));
    }
}
