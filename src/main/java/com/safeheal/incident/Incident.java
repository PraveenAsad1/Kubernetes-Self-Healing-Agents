package com.safeheal.incident;

import java.time.Instant;

public record Incident(
    String id,
    String namespace,
    String workloadUid,
    String workloadName,
    String agentType,
    IncidentState state,
    int attempt,
    int transientRetries,
    int version,
    String claimedBy,
    Instant leaseUntil,
    Instant createdAt,
    Instant updatedAt,
    Instant closedAt
) {}
