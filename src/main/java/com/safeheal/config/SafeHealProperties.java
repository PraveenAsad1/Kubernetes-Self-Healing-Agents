package com.safeheal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "safeheal")
public record SafeHealProperties(
    String kubeconfigContext,
    List<String> allowlistedNamespaces,
    @jakarta.validation.constraints.NotNull Mode mode,
    List<String> allowedContexts
) {
    public enum Mode {
        KUBECONFIG, IN_CLUSTER
    }

    public SafeHealProperties {
        if (allowedContexts == null || allowedContexts.isEmpty()) {
            allowedContexts = List.of("kind-safeheal-dev");
        }
        if (mode != null) {
            ClusterContextGuard.validateContext(mode, kubeconfigContext, allowedContexts);
        }
    }
}
