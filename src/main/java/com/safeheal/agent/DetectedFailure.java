package com.safeheal.agent;

import java.time.Instant;

public record DetectedFailure(
    WorkloadRef workload,
    String podName,
    String podUid,
    String containerName,
    ContainerTermination termination,
    Instant detectedAt
) {}
