package com.safeheal.agent;

public record WorkloadRef(
    String namespace,
    String kind,
    String name,
    String uid
) {}
