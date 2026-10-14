package com.safeheal.k8s;

import com.safeheal.agent.DetectedFailure;
import com.safeheal.agent.IncidentIntake;
import com.safeheal.agent.OomDetector;
import com.safeheal.agent.PodSnapshot;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class PodInformerSource implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(PodInformerSource.class);

    private final KubernetesClient client;
    private final FabricClusterReader clusterReader;
    private final OomDetector detector;
    private final IncidentIntake intake;
    private final Set<String> allowlistedNamespaces;
    private final Clock clock;
    private final Instant startedAt;
    private final Duration skewTolerance;
    private final Duration resyncPeriod;

    private final AtomicLong failureCount = new AtomicLong();
    private final List<SharedIndexInformer<Pod>> informers = new ArrayList<>();
    private volatile boolean running = false;

    public PodInformerSource(
        KubernetesClient client,
        FabricClusterReader clusterReader,
        OomDetector detector,
        IncidentIntake intake,
        Set<String> allowlistedNamespaces,
        Clock clock,
        Duration resyncPeriod,
        Duration skewTolerance
    ) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.clusterReader = Objects.requireNonNull(clusterReader, "clusterReader must not be null");
        this.detector = Objects.requireNonNull(detector, "detector must not be null");
        this.intake = Objects.requireNonNull(intake, "intake must not be null");
        this.allowlistedNamespaces = allowlistedNamespaces != null ? Set.copyOf(allowlistedNamespaces) : Set.of();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.startedAt = clock.instant();
        this.resyncPeriod = resyncPeriod != null ? resyncPeriod : Duration.ofSeconds(60);
        this.skewTolerance = skewTolerance != null ? skewTolerance : Duration.ofMinutes(2);
    }

    public PodInformerSource(
        KubernetesClient client,
        FabricClusterReader clusterReader,
        OomDetector detector,
        IncidentIntake intake,
        Set<String> allowlistedNamespaces,
        Clock clock
    ) {
        this(client, clusterReader, detector, intake, allowlistedNamespaces, clock, Duration.ofSeconds(60), Duration.ofMinutes(2));
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;

        // Empty allowlist fails CLOSED (watch nothing, log a warning), never inAnyNamespace
        if (allowlistedNamespaces.isEmpty()) {
            logger.warn("No allowlisted namespaces configured. Failing closed (watching nothing).");
            return;
        }

        for (String ns : allowlistedNamespaces) {
            if (!OomDetector.HARD_DENYLIST.contains(ns)) {
                registerInformerForNamespace(ns);
            }
        }
    }

    private void registerInformerForNamespace(String namespace) {
        long resyncMillis = resyncPeriod.toMillis();
        SharedIndexInformer<Pod> informer = client.pods().inNamespace(namespace).runnableInformer(resyncMillis);

        informer.addEventHandler(new ResourceEventHandler<>() {
            @Override
            public void onAdd(Pod pod) {
                handlePod(pod);
            }

            @Override
            public void onUpdate(Pod oldPod, Pod newPod) {
                handlePod(newPod);
            }

            @Override
            public void onDelete(Pod pod, boolean deletedFinalStateUnknown) {
                // Ignore deletions in detector
            }
        });

        informer.start();
        informers.add(informer);
    }

    public void handlePod(Pod pod) {
        if (pod == null) {
            return;
        }

        try {
            PodSnapshot snapshot = clusterReader.toSnapshot(pod);
            if (snapshot == null) {
                return;
            }

            Optional<DetectedFailure> failure = detector.detect(snapshot);
            if (failure.isPresent()) {
                DetectedFailure detected = failure.get();
                // Avoid replaying terminations that finished prior to startedAt minus skewTolerance
                Instant finishedAt = detected.termination().finishedAt();
                if (finishedAt != null && finishedAt.isBefore(startedAt.minus(skewTolerance))) {
                    return;
                }
                intake.intake(detected);
            }
        } catch (Exception e) {
            failureCount.incrementAndGet();
            String ns = pod.getMetadata() != null ? pod.getMetadata().getNamespace() : "unknown";
            String name = pod.getMetadata() != null ? pod.getMetadata().getName() : "unknown";
            String uid = pod.getMetadata() != null ? pod.getMetadata().getUid() : "unknown";
            logger.error("Failed processing pod: {}/{}/{}", ns, name, uid, e);
        }
        // Note: Errors (e.g. OutOfMemoryError) are never swallowed and propagate up
    }

    public long getFailureCount() {
        return failureCount.get();
    }

    public List<SharedIndexInformer<Pod>> getInformers() {
        return List.copyOf(informers);
    }

    @Override
    public synchronized void close() {
        running = false;
        for (SharedIndexInformer<Pod> informer : informers) {
            try {
                informer.close();
            } catch (Exception ignored) {}
        }
        informers.clear();
    }
}
