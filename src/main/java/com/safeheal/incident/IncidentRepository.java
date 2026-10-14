package com.safeheal.incident;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IncidentRepository {

    CreateResult create(Incident incident);

    Optional<Incident> findById(String id);

    Incident applyTransition(Incident incident, IncidentEvent event, Transition transition);

    Optional<Incident> claimNext(String workerId, Duration leaseDuration, Instant now);

    void heartbeat(String id, String workerId, Instant now, Duration leaseDuration);

    List<Incident> findResumable(Instant now);

    Instant cooldownUntil(String namespace, String workloadUid);

    int appliedRemediationsSince(Instant since);

    sealed interface CreateResult {
        record Created(Incident incident) implements CreateResult {}
        record Duplicate(String existingIncidentId) implements CreateResult {}
    }
}
