# Architectural Decision Records (ADRs)

## ADR 1: Persistent FSM via Postgres
**Decision:** We persist the Incident FSM in Postgres.

## ADR 2: Optimistic Locking for Incident Updates
**Decision:** Use a `version` field on the `incidents` table for optimistic locking. Lock/dedupe key is `(namespace, workload_uid)`.

## ADR 3: Crash Safety via Intent Log
**Decision:** Implement `remediation_intents` (`pending`, `applied`, `failed`, `abandoned`). Escaping from REMEDIATING requires reconciling with the cluster and setting a terminal intent.

## ADR 4: Redaction and Whitelisting of Evidence
**Decision:** Use explicit whitelisting for pod spec fields (no env values), redaction for logs (`redacted_logs`), and size caps. Redaction is best-effort.

## ADR 5: Type-Level `ApprovedAction`
**Decision:** `ApprovedAction` is a final class with a package-private constructor, carrying `proposal_hash` and `expiry`. Creatable only from proposal, verified matching approval, and fresh policy re-check. Before patching, we verify lease and incident version.

## ADR 6: Concrete Definition of Verification
**Decision:** Separation of `rolloutTimeout` (5m) and `stabilityWindow` (max(10m, 2x observed crash interval)).

## ADR 7: Concurrency via Lease-Based Claims
**Decision:** Use `claimed_by` and `lease_until` fields with heartbeats.

## ADR 8: Prompt-Injection Testing Strategy
**Decision:** Tests simulate an LLM explicitly obeying the injection, asserting that typing and policy block it.

## ADR 9: Enforced Append-Only Tables
**Decision:** Use two DB roles (migration owner vs app role). Revoke `UPDATE` and `DELETE` grants for the app role on `incident_transitions` and `audit_events`.

## ADR 10: Static Knowledge SOPs
**Decision:** Static markdown SOPs per agent are loaded directly into the prompt.

## ADR 11: Specific LLM Foundation Model (Config Property)
**Decision:** The LLM model is not hardcoded. Must be set via configuration; startup fails if unset.

## ADR 12: Real-time Dashboard SSE Integration
**Decision:** Use Server-Sent Events (SSE) with `Last-Event-ID`.

## ADR 13: Agent Resource Profiling
**Decision:** Defer static sizing rules; resource profiling measured during Helm chart phase.

## ADR 14: Limits & Cooldown Tracking
**Decision:** Cooldown relies on the max `closed_at` for a workload. Hourly caps sum `remediation_intents` with `status=applied` within the last hour. Recurrence (second OOM within 24h) beats cooldown and escalates immediately. Blocked detections yield 1 audit event per cooldown window, no incident.

## ADR 15: No Auto-Revert on Failed Verification
**Context:** If a patch (e.g. memory increase) fails to resolve the issue, should we revert it?
**Decision:** No auto-revert in v1. The incident will ESCALATE and describe the state.

## ADR 16: Revision Selection for Rollback (Agent 2)
**Context:** How does the agent know which revision to roll back to?
**Decision:** A `revision_health` table fed by an informer records revision history. The rollback target is computed strictly by code (exactly one revision back, must still exist). The LLM does NOT choose revisions.

## ADR 17: REMEDIATING Conflict Handling
**Context:** A patch encounters a `resourceVersion` conflict.
**Decision:** A conflict voids the approval. The system transitions back to INVESTIGATING (incrementing attempt) to request a fresh proposal and approval, or ESCALATED if budget exhausted. No retrying the same approved patch.
