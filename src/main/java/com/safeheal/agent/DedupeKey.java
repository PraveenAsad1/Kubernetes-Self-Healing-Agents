package com.safeheal.agent;

public record DedupeKey(
    String podUid,
    String containerName,
    int restartCount
) {}
