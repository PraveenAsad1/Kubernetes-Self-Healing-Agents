package com.safeheal.incident;

import java.time.Duration;

public record FsmConfig(int maxAttempts, int maxTransientRetries, Duration approvalTtl) {
    public FsmConfig {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (maxTransientRetries < 0) throw new IllegalArgumentException("maxTransientRetries must be >= 0");
        if (approvalTtl == null || approvalTtl.isNegative() || approvalTtl.isZero()) {
            throw new IllegalArgumentException("approvalTtl must be positive");
        }
    }
}
