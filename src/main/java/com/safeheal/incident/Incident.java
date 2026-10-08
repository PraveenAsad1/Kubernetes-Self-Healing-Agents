package com.safeheal.incident;

import java.time.Instant;
import java.util.Map;

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
    Instant createdAt,
    Instant updatedAt,
    Instant closedAt
) {}
