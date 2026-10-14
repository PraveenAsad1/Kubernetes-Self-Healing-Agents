package com.safeheal.k8s;

import com.safeheal.config.ClusterContextGuard;
import com.safeheal.config.SafeHealProperties;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SafeHealProperties.class)
public class K8sConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public KubernetesClient kubernetesClient(SafeHealProperties properties) {
        if (properties.mode() == SafeHealProperties.Mode.IN_CLUSTER) {
            return new KubernetesClientBuilder().build();
        }

        // KUBECONFIG mode: read active context from Fabric8 config or properties
        Config config = Config.autoConfigure(null);
        String activeContext = properties.kubeconfigContext();
        if (activeContext == null || activeContext.isBlank()) {
            if (config.getCurrentContext() != null) {
                activeContext = config.getCurrentContext().getName();
            }
        }

        // Enforce ADR 19 / Rule 16 context allowlist
        ClusterContextGuard.validateContext(properties.mode(), activeContext, properties.allowedContexts());

        return new KubernetesClientBuilder().withConfig(config).build();
    }

    @Bean
    @ConditionalOnMissingBean
    public FabricClusterReader fabricClusterReader(KubernetesClient client) {
        return new FabricClusterReader(client);
    }
}
