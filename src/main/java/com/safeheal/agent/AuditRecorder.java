package com.safeheal.agent;

import java.time.Instant;

@FunctionalInterface
public interface AuditRecorder {
    void recordAuditEvent(String incidentId, String eventType, String payload, Instant timestamp);
}
