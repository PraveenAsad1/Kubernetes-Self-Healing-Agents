package com.safeheal.agent;

import java.util.List;
import java.util.Map;

public record PodSnapshot(
    String name,
    String namespace,
    String uid,
    WorkloadRef ownerWorkload,
    Map<String, String> labels,
    List<ContainerTermination> appContainerTerminations,
    List<ContainerTermination> initContainerTerminations,
    List<ContainerSpec> containerSpecs,
    boolean deleted
) {}
