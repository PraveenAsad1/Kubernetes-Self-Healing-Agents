package com.safeheal.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class EvidenceCollector {

    private final ClusterReader clusterReader;
    private final Redactor redactor;

    public record CollectedEvidence(
        String podName,
        String containerName,
        String redactedLogs,
        List<String> k8sEvents,
        List<ContainerSpec> whitelistedSpecs
    ) {}

    public EvidenceCollector(ClusterReader clusterReader, Redactor redactor) {
        this.clusterReader = Objects.requireNonNull(clusterReader, "clusterReader must not be null");
        this.redactor = Objects.requireNonNull(redactor, "redactor must not be null");
    }

    public CollectedEvidence collect(PodSnapshot pod, String containerName, int maxLogBytes) throws ClusterReadException {
        Objects.requireNonNull(pod, "pod must not be null");
        Objects.requireNonNull(containerName, "containerName must not be null");

        // 1. Fetch previous logs through ClusterReader (throws ClusterReadException on failure; NOT swallowed)
        String rawLogs = clusterReader.previousLogs(pod.namespace(), pod.name(), containerName, maxLogBytes)
            .orElse("");

        // 2. Pass logs through Redactor
        String redactedLogs = redactor.redact(rawLogs);

        // 3. Fetch cluster events (throws ClusterReadException on failure; NOT swallowed)
        List<String> events = clusterReader.events(pod.namespace(), pod.uid());

        // 4. Extract whitelisted container specs (image, resources, command, restart counts; NEVER env values)
        List<ContainerSpec> whitelistedSpecs = pod.containerSpecs() != null
            ? pod.containerSpecs().stream()
                .map(spec -> new ContainerSpec(
                    spec.name(),
                    spec.image(),
                    spec.command() != null ? List.copyOf(spec.command()) : List.of(),
                    spec.requests() != null ? Map.copyOf(spec.requests()) : Map.of(),
                    spec.limits() != null ? Map.copyOf(spec.limits()) : Map.of()
                ))
                .toList()
            : List.of();

        return new CollectedEvidence(
            pod.name(),
            containerName,
            redactedLogs,
            events,
            whitelistedSpecs
        );
    }
}
