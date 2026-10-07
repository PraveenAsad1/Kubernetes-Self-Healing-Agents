package com.safeheal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

@ConfigurationProperties(prefix = "safeheal")
public record SafeHealProperties(
    String kubeconfigContext,
    List<String> allowlistedNamespaces
) {}
