package com.safeheal.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = SafeHealPropertiesTest.TestConfig.class)
@TestPropertySource(properties = {
    "safeheal.kubeconfig-context=dev-cluster",
    "safeheal.allowlisted-namespaces=default,backend"
})
public class SafeHealPropertiesTest {

    @EnableConfigurationProperties(SafeHealProperties.class)
    static class TestConfig {}

    @Autowired
    private SafeHealProperties properties;

    @Test
    public void propertiesAreBoundCorrectly() {
        assertEquals("dev-cluster", properties.kubeconfigContext());
        assertEquals(List.of("default", "backend"), properties.allowlistedNamespaces());
    }
}
