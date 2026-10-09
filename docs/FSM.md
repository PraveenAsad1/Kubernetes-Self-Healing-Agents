# FSM Transition Table

| Current State | Event | Guard | Next State | Side Effects |
|---|---|---|---|---|
| DETECTED | ProcessingStarted | None | INVESTIGATING | LOG |
| DETECTED | WorkloadDeleted | None | CANCELLED | LOG |
| DETECTED | Recurrence | None | ESCALATED | LOG |
| INVESTIGATING | EvidenceGathered | None | DIAGNOSING | RESET_TRANSIENT_RETRIES, STORE_EVIDENCE |
| INVESTIGATING | K8sReadFailed | transientRetries < maxTransientRetries | INVESTIGATING | INCREMENT_TRANSIENT_RETRY |
| INVESTIGATING | K8sReadFailed | transientRetries >= maxTransientRetries | ESCALATED | LOG |
| INVESTIGATING | Timeout | None | ESCALATED | LOG |
| INVESTIGATING | WorkloadDeleted | None | CANCELLED | LOG |
| DIAGNOSING | ProposalAccepted | None | AWAITING_APPROVAL | RESET_TRANSIENT_RETRIES, STORE_PROPOSAL |
| DIAGNOSING | LlmFailed | transientRetries < maxTransientRetries | DIAGNOSING | INCREMENT_TRANSIENT_RETRY |
| DIAGNOSING | LlmFailed | transientRetries >= maxTransientRetries | ESCALATED | LOG |
| DIAGNOSING | LlmInvalid | transientRetries < maxTransientRetries | DIAGNOSING | INCREMENT_TRANSIENT_RETRY |
| DIAGNOSING | LlmInvalid | transientRetries >= maxTransientRetries | ESCALATED | LOG |
| DIAGNOSING | PolicyDenied | None | ESCALATED | LOG |
| DIAGNOSING | Timeout | None | ESCALATED | LOG |
| DIAGNOSING | WorkloadDeleted | None | CANCELLED | LOG |
| AWAITING_APPROVAL | Approved | approval is not expired | REMEDIATING | CREATE_INTENT, RESET_TRANSIENT_RETRIES |
| AWAITING_APPROVAL | Rejected | None | ESCALATED | LOG |
| AWAITING_APPROVAL | Timeout | None | ESCALATED | LOG |
| AWAITING_APPROVAL | WorkloadDeleted | None | CANCELLED | LOG |
| REMEDIATING | ExecutionPolicyDenied | None | ESCALATED | TERMINATE_INTENT |
| REMEDIATING | Conflict | attempt < maxAttempts | INVESTIGATING | INCREMENT_ATTEMPT, RESET_TRANSIENT_RETRIES, TERMINATE_INTENT |
| REMEDIATING | Conflict | attempt >= maxAttempts | ESCALATED | TERMINATE_INTENT |
| REMEDIATING | PatchFailed | transientRetries < maxTransientRetries | REMEDIATING | INCREMENT_TRANSIENT_RETRY |
| REMEDIATING | PatchFailed | transientRetries >= maxTransientRetries | ESCALATED | TERMINATE_INTENT |
| REMEDIATING | Applied | None | VERIFYING | MARK_INTENT_APPLIED, RESET_TRANSIENT_RETRIES |
| REMEDIATING | ReconcileResume | None | VERIFYING | SYNC_INTENT_STATE |
| REMEDIATING | Timeout | None | ESCALATED | TERMINATE_INTENT |
| REMEDIATING | WorkloadDeleted | None | CANCELLED | TERMINATE_INTENT |
| VERIFYING | Verified | None | RESOLVED | LOG |
| VERIFYING | VerificationFailed | None | REFLECTING | LOG |
| VERIFYING | Timeout | None | REFLECTING | LOG |
| VERIFYING | WorkloadDeleted | None | CANCELLED | LOG |
| VERIFYING | Recurrence | None | ESCALATED | LOG |
| REFLECTING | ReflectionCompleted | attempt < maxAttempts | INVESTIGATING | INCREMENT_ATTEMPT, RESET_TRANSIENT_RETRIES |
| REFLECTING | ReflectionCompleted | attempt >= maxAttempts | ESCALATED | LOG |
