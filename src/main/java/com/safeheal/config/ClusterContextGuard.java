package com.safeheal.config;

import java.util.List;

public class ClusterContextGuard {

    public static void validateContext(SafeHealProperties.Mode mode, String activeContext, List<String> allowedContexts) {
        if (mode == SafeHealProperties.Mode.IN_CLUSTER) {
            return;
        }

        if (activeContext == null || activeContext.isBlank()) {
            throw new IllegalStateException("Active kubeconfig context cannot be null or blank in KUBECONFIG mode");
        }

        if (allowedContexts == null || !allowedContexts.contains(activeContext)) {
            throw new IllegalStateException("Active context '" + activeContext + "' is not in the allowlist: " + allowedContexts);
        }
    }
}
