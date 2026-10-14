package com.safeheal.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class OomDetector {

    public static final Set<String> HARD_DENYLIST = Set.of("kube-system", "kube-public", "kube-node-lease");

    private final Clock clock;
    private final Duration recencyWindow;
    private final Duration skewTolerance;
    private final Set<String> allowlistedNamespaces;

    public OomDetector(Clock clock, Duration recencyWindow, Duration skewTolerance, Set<String> allowlistedNamespaces) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.recencyWindow = Objects.requireNonNull(recencyWindow, "recencyWindow must not be null");
        this.skewTolerance = Objects.requireNonNull(skewTolerance, "skewTolerance must not be null");
        this.allowlistedNamespaces = allowlistedNamespaces != null ? Set.copyOf(allowlistedNamespaces) : Set.of();
    }

    public OomDetector(Clock clock, Set<String> allowlistedNamespaces) {
        this(clock, Duration.ofMinutes(10), Duration.ofMinutes(2), allowlistedNamespaces);
    }

    public Optional<DetectedFailure> detect(PodSnapshot pod) {
        if (pod == null || pod.deleted()) {
            return Optional.empty();
        }

        String ns = pod.namespace();
        if (ns == null || HARD_DENYLIST.contains(ns)) {
            return Optional.empty();
        }

        if (!allowlistedNamespaces.isEmpty() && !allowlistedNamespaces.contains(ns)) {
            return Optional.empty();
        }

        WorkloadRef owner = pod.ownerWorkload();
        if (owner == null || !"Deployment".equals(owner.kind())) {
            return Optional.empty();
        }

        Instant now = clock.instant();
        Instant windowStart = now.minus(recencyWindow);
        Instant maxFutureAllowed = now.plus(skewTolerance);

        // Check app containers only; init containers are ignored in Phase 2 (recorded in ADR 22)
        List<ContainerTermination> terminations = pod.appContainerTerminations();
        if (terminations == null || terminations.isEmpty()) {
            return Optional.empty();
        }

        for (ContainerTermination termination : terminations) {
            if ("OOMKilled".equals(termination.reason()) && termination.exitCode() == 137) {
                Instant finishedAt = termination.finishedAt();
                if (finishedAt != null && !finishedAt.isBefore(windowStart) && !finishedAt.isAfter(maxFutureAllowed)) {
                    return Optional.of(new DetectedFailure(
                        owner,
                        pod.name(),
                        pod.uid(),
                        termination.containerName(),
                        termination,
                        now
                    ));
                }
            }
        }

        return Optional.empty();
    }
}
