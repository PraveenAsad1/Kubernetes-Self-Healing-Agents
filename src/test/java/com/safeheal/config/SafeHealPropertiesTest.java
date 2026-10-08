package com.safeheal.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

public class SafeHealPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    public void validContextPasses() {
        contextRunner
            .withPropertyValues(
                "safeheal.mode=KUBECONFIG",
                "safeheal.kubeconfig-context=kind-safeheal-dev"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
            });
    }

    @Test
    public void blankContextInKubeconfigModeFails() {
        contextRunner
            .withPropertyValues(
                "safeheal.mode=KUBECONFIG",
                "safeheal.kubeconfig-context="
            )
            .run(context -> {
                assertThat(context).hasFailed();
            });
    }

    @Test
    public void nullModeFailsValidation() {
        contextRunner
            .withPropertyValues(
                "safeheal.kubeconfig-context=kind-safeheal-dev"
            )
            .run(context -> {
                assertThat(context).hasFailed();
            });
    }

    @EnableConfigurationProperties(SafeHealProperties.class)
    @org.springframework.boot.autoconfigure.SpringBootApplication
    static class TestConfig {}
}
