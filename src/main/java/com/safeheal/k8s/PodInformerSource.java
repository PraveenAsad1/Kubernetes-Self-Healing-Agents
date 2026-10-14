package com.safeheal.k8s;

import com.safeheal.agent.DetectedFailure;
import com.safeheal.agent.IncidentIntake;
import com.safeheal.agent.OomDetector;
import com.safeheal.agent.PodSnapshot;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;

import java.io.Closeable;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class PodInformerSource implements Closeable {

    private final KubernetesClient client;
    private final FabricClusterReader clusterReader;
    private final OomDetector detector;
    private final IncidentIntake intake;
    private final Set<String> allowlistedNamespaces;
    private final Clock clock;
    private final Instant startedAt;

    private final List<SharedIndexInformer<Pod>> informers = new ArrayList<>();
    private volatile boolean running = false;

    public PodInformerSource(
        KubernetesClient client,
        FabricClusterReader clusterReader,
        OomDetector detector,
        IncidentIntake intake,
        Set<String> allowlistedNamespaces,
        Clock clock
    ) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.clusterReader = Objects.requireNonNull(clusterReader, "clusterReader must not be null");
        this.detector = Objects.requireNonNull(detector, "detector must not be null");
        this.intake = Objects.requireNonNull(intake, "intake must not be null");
        this.allowlistedNamespaces = allowlistedNamespaces != null ? Set.copyOf(allowlistedNamespaces) : Set.of();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.startedAt = clock.instant();
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;

        if (allowlistedNamespaces.isEmpty()) {
            registerInformerForNamespace(null);
        } else {
            for (String ns : allowlistedNamespaces) {
                if (!OomDetector.HARD_DENYLIST.contains(ns)) {
                    registerInformerForNamespace(ns);
                }
            }
        }
    }

    private void registerInformerForNamespace(String namespace) {
        var operation = namespace != null ? client.pods().inNamespace(namespace) : client.pods().inAnyNamespace();
        SharedIndexInformer<Pod> informer = operation.runnableInformer(0);

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
                intake.intake(failure.get(), snapshot.uid());
            }
        } catch (Exception e) {
            // Best-effort ingestion failure log
        }
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
