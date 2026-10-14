package com.safeheal.agent;

import java.time.Instant;

public record ContainerTermination(
    String containerName,
    String reason,
    int exitCode,
    Instant finishedAt,
    int restartCount
) {}
