package com.safeheal.k8s;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterContextGuardStartupTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(K8sConfiguration.class));

    @Test
    @DisplayName("Disallowed kubeconfig context causes application startup to fail")
    void disallowedKubeconfigContextFailsStartup() {
        contextRunner
            .withPropertyValues(
                "safeheal.mode=KUBECONFIG",
                "safeheal.kubeconfig-context=production-cluster",
                "safeheal.allowed-contexts=kind-safeheal-dev"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Active context 'production-cluster' is not in the allowlist: [kind-safeheal-dev]");
            });
    }

    @Test
    @DisplayName("Allowed kubeconfig context allows application startup to succeed")
    void allowedKubeconfigContextSucceeds() {
        contextRunner
            .withPropertyValues(
                "safeheal.mode=KUBECONFIG",
                "safeheal.kubeconfig-context=kind-safeheal-dev",
                "safeheal.allowed-contexts=kind-safeheal-dev"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(K8sConfiguration.class);
            });
    }

    @Test
    @DisplayName("IN_CLUSTER mode skips context allowlist validation even if context is disallowed")
    void inClusterModeSkipsContextValidation() {
        contextRunner
            .withPropertyValues(
                "safeheal.mode=IN_CLUSTER",
                "safeheal.kubeconfig-context=unauthorized-external-cluster",
                "safeheal.allowed-contexts=kind-safeheal-dev"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
            });
    }
}
