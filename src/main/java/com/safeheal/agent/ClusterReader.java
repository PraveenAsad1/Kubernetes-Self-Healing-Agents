package com.safeheal.agent;

import java.util.List;
import java.util.Optional;

public interface ClusterReader {
    Optional<WorkloadRef> readWorkload(String namespace, String kind, String name) throws ClusterReadException;
    List<PodSnapshot> podsOf(WorkloadRef workload) throws ClusterReadException;
    Optional<String> previousLogs(String namespace, String podName, String containerName, int maxBytes) throws ClusterReadException;
    List<String> events(String namespace, String resourceUid) throws ClusterReadException;
}
