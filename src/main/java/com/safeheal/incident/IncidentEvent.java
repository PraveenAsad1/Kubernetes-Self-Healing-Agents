package com.safeheal.incident;

import java.time.Instant;

public sealed interface IncidentEvent {
    record ProcessingStarted() implements IncidentEvent {}
    record EvidenceGathered() implements IncidentEvent {}
    record K8sReadFailed() implements IncidentEvent {}
    record LlmInvalid() implements IncidentEvent {}
    record LlmFailed() implements IncidentEvent {}
    record PolicyDenied() implements IncidentEvent {}
    record ExecutionPolicyDenied() implements IncidentEvent {}
    record ProposalAccepted() implements IncidentEvent {}
    record Approved(String proposalId, String proposalHash, String approverIdentity, Instant approvedUntil) implements IncidentEvent {}
    record Rejected() implements IncidentEvent {}
    record Timeout() implements IncidentEvent {}
    record WorkloadDeleted() implements IncidentEvent {}
    record Conflict() implements IncidentEvent {}
    record Applied() implements IncidentEvent {}
    record PatchFailed() implements IncidentEvent {}
    record Verified() implements IncidentEvent {}
    record VerificationFailed() implements IncidentEvent {}
    record ReflectionCompleted() implements IncidentEvent {}
    record ReconcileResume() implements IncidentEvent {}
    record Recurrence() implements IncidentEvent {}
}
