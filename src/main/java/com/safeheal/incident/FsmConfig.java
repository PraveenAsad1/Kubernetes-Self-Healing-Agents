package com.safeheal.incident;

import java.time.Duration;

public record FsmConfig(
    int maxAttempts,
    int maxTransientRetries,
    Duration approvalTtl
) {}
