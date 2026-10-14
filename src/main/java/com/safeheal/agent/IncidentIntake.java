package com.safeheal.agent;

import com.safeheal.incident.Incident;
import com.safeheal.incident.IncidentRepository;
import com.safeheal.incident.IncidentState;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class IncidentIntake {

    private final IncidentRepository incidentRepository;
    private final TriageRouter triageRouter;
    private final Clock clock;
    private final AuditRecorder auditRecorder;

    // In-memory dedupe key (podUid, container, restartCount) in front of the DB
    private final Set<DedupeKey> seenTerminations = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Tracks cooldown window for which an audit event was already sent per workload (namespace/workloadUid -> cooldownUntil)
    private final ConcurrentHashMap<String, Instant> workloadAuditSentForWindow = new ConcurrentHashMap<>();

    public IncidentIntake(
        IncidentRepository incidentRepository,
        TriageRouter triageRouter,
        Clock clock,
        AuditRecorder auditRecorder
    ) {
        this.incidentRepository = Objects.requireNonNull(incidentRepository, "incidentRepository must not be null");
        this.triageRouter = Objects.requireNonNull(triageRouter, "triageRouter must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditRecorder = auditRecorder != null ? auditRecorder : (id, event, payload, time) -> {};
    }

    public Optional<Incident> intake(DetectedFailure failure, String podUid) {
        if (failure == null || failure.workload() == null || failure.termination() == null) {
            return Optional.empty();
        }

        // 1. In-memory dedupe check
        DedupeKey dedupeKey = new DedupeKey(
            podUid != null ? podUid : failure.podName(),
            failure.containerName(),
            failure.termination().restartCount()
        );
        if (!seenTerminations.add(dedupeKey)) {
            // Already seen this termination event; silent no-op
            return Optional.empty();
        }

        Instant now = clock.instant();
        String namespace = failure.workload().namespace();
        String workloadUid = failure.workload().uid();

        // 2. Cooldown check: call cooldownUntil(namespace, workloadUid) before create()
        Instant cooldownUntil = incidentRepository.cooldownUntil(namespace, workloadUid);
        if (cooldownUntil != null && now.isBefore(cooldownUntil)) {
            // Inside cooldown: exactly ONE audit event per workload per window, NO incident row
            String workloadKey = namespace + "/" + workloadUid;
            Instant recordedWindow = workloadAuditSentForWindow.putIfAbsent(workloadKey, cooldownUntil);
            if (recordedWindow == null || !recordedWindow.equals(cooldownUntil)) {
                if (recordedWindow != null) {
                    workloadAuditSentForWindow.replace(workloadKey, recordedWindow, cooldownUntil);
                }
                auditRecorder.recordAuditEvent(
                    null,
                    "DETECTION_BLOCKED_COOLDOWN",
                    "Workload " + workloadKey + " detection blocked by cooldown until " + cooldownUntil,
                    now
                );
            }
            return Optional.empty();
        }

        // 3. Triage routing
        Optional<String> agentTypeOpt = triageRouter.route(failure);
        if (agentTypeOpt.isEmpty()) {
            return Optional.empty();
        }

        // 4. Create incident in DETECTED state
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
            // A duplicate open incident exists for (namespace, workload_uid); silent no-op
            return Optional.empty();
        } else if (result instanceof IncidentRepository.CreateResult.Created created) {
            return Optional.of(created.incident());
        }

        return Optional.empty();
    }
}
