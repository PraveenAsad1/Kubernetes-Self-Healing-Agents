package com.safeheal.k8s;

import com.safeheal.agent.ClusterReadException;
import com.safeheal.agent.ClusterReader;
import com.safeheal.agent.ContainerSpec;
import com.safeheal.agent.ContainerTermination;
import com.safeheal.agent.PodSnapshot;
import com.safeheal.agent.WorkloadRef;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class FabricClusterReader implements ClusterReader {

    private final KubernetesClient client;

    public FabricClusterReader(KubernetesClient client) {
        this.client = Objects.requireNonNull(client, "KubernetesClient must not be null");
    }

    @Override
    public Optional<WorkloadRef> readWorkload(String namespace, String kind, String name) throws ClusterReadException {
        try {
            if ("Deployment".equalsIgnoreCase(kind)) {
                Deployment dep = client.apps().deployments().inNamespace(namespace).withName(name).get();
                if (dep != null && dep.getMetadata() != null && dep.getMetadata().getDeletionTimestamp() == null) {
                    return Optional.of(new WorkloadRef(
                        dep.getMetadata().getNamespace(),
                        "Deployment",
                        dep.getMetadata().getName(),
                        dep.getMetadata().getUid()
                    ));
                }
            }
            return Optional.empty();
        } catch (KubernetesClientException e) {
            throw new ClusterReadException("Failed to read workload " + kind + "/" + name + " in " + namespace, e);
        }
    }

    @Override
    public List<PodSnapshot> podsOf(WorkloadRef workload) throws ClusterReadException {
        try {
            if (workload == null || !"Deployment".equals(workload.kind())) {
                return List.of();
            }

            List<Pod> pods = client.pods().inNamespace(workload.namespace()).list().getItems();
            List<PodSnapshot> snapshots = new ArrayList<>();
            for (Pod pod : pods) {
                WorkloadRef owner = resolveOwnerWorkload(pod);
                if (owner != null && owner.uid().equals(workload.uid())) {
                    snapshots.add(toSnapshot(pod, owner));
                }
            }
            return snapshots;
        } catch (KubernetesClientException e) {
            throw new ClusterReadException("Failed to list pods for workload " + workload, e);
        }
    }

    @Override
    public Optional<String> previousLogs(String namespace, String podName, String containerName, int maxBytes) throws ClusterReadException {
        try {
            var podResource = client.pods().inNamespace(namespace).withName(podName);
            var logResource = containerName != null ? podResource.inContainer(containerName) : podResource;
            String logs = logResource.terminated().getLog();
            if (logs != null && maxBytes > 0) {
                logs = com.safeheal.agent.Redactor.tailBytes(logs, maxBytes);
            }
            return Optional.ofNullable(logs);
        } catch (KubernetesClientException e) {
            throw new ClusterReadException("Failed to fetch previous logs for pod " + podName + "/" + containerName, e);
        }
    }

    @Override
    public List<String> events(String namespace, String resourceUid) throws ClusterReadException {
        try {
            List<Event> eventList = client.v1().events().inNamespace(namespace).list().getItems();
            List<String> result = new ArrayList<>();
            for (Event e : eventList) {
                if (resourceUid == null || (e.getInvolvedObject() != null && resourceUid.equals(e.getInvolvedObject().getUid()))) {
                    String formatted = String.format("[%s] %s: %s",
                        e.getType() != null ? e.getType() : "Normal",
                        e.getReason() != null ? e.getReason() : "Unknown",
                        e.getMessage() != null ? e.getMessage() : ""
                    );
                    result.add(formatted);
                }
            }
            return result;
        } catch (KubernetesClientException e) {
            throw new ClusterReadException("Failed to fetch events in namespace " + namespace, e);
        }
    }

    public PodSnapshot toSnapshot(Pod pod) {
        WorkloadRef owner = resolveOwnerWorkload(pod);
        return toSnapshot(pod, owner);
    }

    public PodSnapshot toSnapshot(Pod pod, WorkloadRef owner) {
        if (pod == null || pod.getMetadata() == null) {
            return null;
        }

        String name = pod.getMetadata().getName();
        String namespace = pod.getMetadata().getNamespace();
        String uid = pod.getMetadata().getUid();
        boolean deleted = pod.getMetadata().getDeletionTimestamp() != null;
        Map<String, String> labels = pod.getMetadata().getLabels() != null
            ? Map.copyOf(pod.getMetadata().getLabels())
            : Map.of();

        List<ContainerTermination> appTerminations = new ArrayList<>();
        if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
            for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
                ContainerTermination term = extractTermination(status);
                if (term != null) {
                    appTerminations.add(term);
                }
            }
        }

        List<ContainerTermination> initTerminations = new ArrayList<>();
        if (pod.getStatus() != null && pod.getStatus().getInitContainerStatuses() != null) {
            for (ContainerStatus status : pod.getStatus().getInitContainerStatuses()) {
                ContainerTermination term = extractTermination(status);
                if (term != null) {
                    initTerminations.add(term);
                }
            }
        }

        List<ContainerSpec> specs = new ArrayList<>();
        if (pod.getSpec() != null && pod.getSpec().getContainers() != null) {
            for (Container c : pod.getSpec().getContainers()) {
                Map<String, String> reqs = extractQuantities(c.getResources() != null ? c.getResources().getRequests() : null);
                Map<String, String> lims = extractQuantities(c.getResources() != null ? c.getResources().getLimits() : null);
                specs.add(new ContainerSpec(
                    c.getName(),
                    c.getImage(),
                    c.getCommand() != null ? List.copyOf(c.getCommand()) : List.of(),
                    reqs,
                    lims
                ));
            }
        }

        return new PodSnapshot(
            name,
            namespace,
            uid,
            owner,
            labels,
            appTerminations,
            initTerminations,
            specs,
            deleted
        );
    }

    public WorkloadRef resolveOwnerWorkload(Pod pod) {
        if (pod == null || pod.getMetadata() == null) {
            return null;
        }

        List<OwnerReference> owners = pod.getMetadata().getOwnerReferences();
        if (owners == null || owners.isEmpty()) {
            // Bare pod -> ineligible
            return null;
        }

        String namespace = pod.getMetadata().getNamespace();

        for (OwnerReference owner : owners) {
            if ("ReplicaSet".equals(owner.getKind())) {
                try {
                    ReplicaSet rs = client.apps().replicaSets().inNamespace(namespace).withName(owner.getName()).get();
                    if (rs == null || rs.getMetadata() == null || rs.getMetadata().getDeletionTimestamp() != null) {
                        return null;
                    }
                    List<OwnerReference> rsOwners = rs.getMetadata().getOwnerReferences();
                    if (rsOwners == null || rsOwners.isEmpty()) {
                        // Ownerless ReplicaSet -> ineligible
                        return null;
                    }
                    for (OwnerReference rsOwner : rsOwners) {
                        if ("Deployment".equals(rsOwner.getKind())) {
                            Deployment dep = client.apps().deployments().inNamespace(namespace).withName(rsOwner.getName()).get();
                            if (dep != null && dep.getMetadata() != null && dep.getMetadata().getDeletionTimestamp() == null) {
                                return new WorkloadRef(
                                    namespace,
                                    "Deployment",
                                    dep.getMetadata().getName(),
                                    dep.getMetadata().getUid()
                                );
                            }
                        }
                    }
                } catch (KubernetesClientException e) {
                    throw new ClusterReadException("Failed resolving ReplicaSet owner for pod " + pod.getMetadata().getName(), e);
                }
            } else if ("Job".equals(owner.getKind())) {
                // Job pod -> ineligible (return Job workload)
                return new WorkloadRef(namespace, "Job", owner.getName(), owner.getUid());
            }
        }

        return null;
    }

    private ContainerTermination extractTermination(ContainerStatus status) {
        if (status == null) {
            return null;
        }

        // Check state.terminated first, then lastState.terminated (user amendment 3)
        ContainerStateTerminated term = null;
        if (status.getState() != null && status.getState().getTerminated() != null) {
            term = status.getState().getTerminated();
        } else if (status.getLastState() != null && status.getLastState().getTerminated() != null) {
            term = status.getLastState().getTerminated();
        }

        if (term == null) {
            return null;
        }

        Instant finishedAt = null;
        if (term.getFinishedAt() != null) {
            try {
                finishedAt = Instant.parse(term.getFinishedAt());
            } catch (Exception e) {
                // Parse fallback
            }
        }

        return new ContainerTermination(
            status.getName(),
            term.getReason(),
            term.getExitCode() != null ? term.getExitCode() : -1,
            finishedAt,
            status.getRestartCount() != null ? status.getRestartCount() : 0
        );
    }

    private Map<String, String> extractQuantities(Map<String, Quantity> map) {
        if (map == null) return Map.of();
        Map<String, String> result = new HashMap<>();
        map.forEach((k, v) -> result.put(k, v != null ? v.getAmount() + (v.getFormat() != null ? v.getFormat() : "") : ""));
        return Map.copyOf(result);
    }
}
