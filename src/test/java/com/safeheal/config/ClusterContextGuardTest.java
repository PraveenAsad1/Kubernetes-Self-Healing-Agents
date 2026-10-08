package com.safeheal.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ClusterContextGuardTest {

    @Test
    void testAllowedContextPasses() {
        assertDoesNotThrow(() -> ClusterContextGuard.validateContext(
            SafeHealProperties.Mode.KUBECONFIG,
            "kind-safeheal-dev",
            List.of("kind-safeheal-dev")
        ));
    }

    @Test
    void testDeniedContextThrows() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ClusterContextGuard.validateContext(
            SafeHealProperties.Mode.KUBECONFIG,
            "minikube",
            List.of("kind-safeheal-dev")
        ));
        assertTrue(ex.getMessage().contains("not in the allowlist"));
    }

    @Test
    void testBlankContextThrows() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ClusterContextGuard.validateContext(
            SafeHealProperties.Mode.KUBECONFIG,
            "  ",
            List.of("kind-safeheal-dev")
        ));
        assertTrue(ex.getMessage().contains("cannot be null or blank"));
    }

    @Test
    void testInClusterSkipsCheck() {
        assertDoesNotThrow(() -> ClusterContextGuard.validateContext(
            SafeHealProperties.Mode.IN_CLUSTER,
            "minikube",
            List.of("kind-safeheal-dev")
        ));
    }
}
