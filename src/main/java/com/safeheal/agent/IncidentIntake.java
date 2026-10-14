package com.safeheal.agent;

import com.safeheal.incident.Incident;
import com.safeheal.incident.IncidentRepository;
import com.safeheal.incident.IncidentState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class IncidentIntake {

    private final IncidentRepository incidentRepository;
    private final TriageRouter triageRouter;
    private final Clock clock;
    private final AuditRecorder auditRecorder;

    // Bounded in-memory dedupe structure (max 10_000 entries, expires after 2x recency window)
    private final BoundedTtlMap<DedupeKey, Boolean> seenTerminations;

    // Bounded audit event tracker per workload window (max 10_000 entries)
    private final BoundedTtlMap<String, Instant> workloadAuditSentForWindow;

    public IncidentIntake(
        IncidentRepository incidentRepository,
        TriageRouter triageRouter,
        Clock clock,
        AuditRecorder auditRecorder,
        Duration recencyWindow
    ) {
        this.incidentRepository = Objects.requireNonNull(incidentRepository, "incidentRepository must not be null");
        this.triageRouter = Objects.requireNonNull(triageRouter, "triageRouter must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditRecorder = auditRecorder != null ? auditRecorder : (id, event, payload, time) -> {};
        Duration ttl = (recencyWindow != null ? recencyWindow : Duration.ofMinutes(10)).multipliedBy(2);
        this.seenTerminations = new BoundedTtlMap<>(10_000, ttl, clock);
        this.workloadAuditSentForWindow = new BoundedTtlMap<>(10_000, ttl, clock);
    }

    public IncidentIntake(
        IncidentRepository incidentRepository,
        TriageRouter triageRouter,
        Clock clock,
        AuditRecorder auditRecorder
    ) {
        this(incidentRepository, triageRouter, clock, auditRecorder, Duration.ofMinutes(10));
    }

    public Optional<Incident> intake(DetectedFailure failure) {
        if (failure == null || failure.workload() == null || failure.termination() == null) {
            return Optional.empty();
        }

        DedupeKey dedupeKey = new DedupeKey(
            failure.podUid() != null ? failure.podUid() : failure.podName(),
            failure.containerName(),
            failure.termination().restartCount()
        );

        // 1. In-memory dedupe check
        if (seenTerminations.containsKey(dedupeKey)) {
            return Optional.empty();
        }

        // 2. Triage before cooldown check
        Optional<String> agentTypeOpt = triageRouter.route(failure);
        if (agentTypeOpt.isEmpty()) {
            // Outcome is final (not-OOM): record key and return empty
            seenTerminations.put(dedupeKey, Boolean.TRUE);
            return Optional.empty();
        }

        Instant now = clock.instant();
        String namespace = failure.workload().namespace();
        String workloadUid = failure.workload().uid();

        // 3. Cooldown check: call cooldownUntil(namespace, workloadUid) before create()
        Instant cooldownUntil = incidentRepository.cooldownUntil(namespace, workloadUid);
        if (cooldownUntil != null && now.isBefore(cooldownUntil)) {
            // A cooldown-blocked termination is NOT recorded in dedupeKey
            String workloadKey = namespace + "/" + workloadUid;
            Instant recordedWindow = workloadAuditSentForWindow.get(workloadKey);
            if (recordedWindow == null || !recordedWindow.equals(cooldownUntil)) {
                workloadAuditSentForWindow.put(workloadKey, cooldownUntil);
                auditRecorder.recordAuditEvent(
                    null,
                    "DETECTION_BLOCKED_COOLDOWN",
                    "Workload " + workloadKey + " detection blocked by cooldown until " + cooldownUntil,
                    now
                );
            }
            return Optional.empty();
        }

        // 4. Create incident: on any exception remove key and rethrow
        try {
            String incidentId = UUID.randomUUID().toString();
            Incident incident = new Incident(
                incidentId,
                namespace,
                workloadUid,
                failure.workload().name(),
                agentTypeOpt.get(),
                IncidentState.DETECTED,
                1,
                0,
                1,
                null,
                null,
                now,
                now,
                null
            );

            IncidentRepository.CreateResult result = incidentRepository.create(incident);
            if (result instanceof IncidentRepository.CreateResult.Duplicate) {
                // Outcome is final (duplicate): record dedupeKey
                seenTerminations.put(dedupeKey, Boolean.TRUE);
                return Optional.empty();
            } else if (result instanceof IncidentRepository.CreateResult.Created created) {
                // Outcome is final (created): record dedupeKey
                seenTerminations.put(dedupeKey, Boolean.TRUE);
                return Optional.of(created.incident());
            }

            return Optional.empty();
        } catch (Throwable t) {
            seenTerminations.remove(dedupeKey);
            throw t;
        }
    }

    int dedupeCacheSize() {
        return seenTerminations.size();
    }
}
