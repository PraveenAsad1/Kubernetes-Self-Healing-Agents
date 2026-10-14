package com.safeheal.agent;

import java.util.List;
import java.util.Map;

public record ContainerSpec(
    String name,
    String image,
    List<String> command,
    Map<String, String> requests,
    Map<String, String> limits
) {}
