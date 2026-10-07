# Architecture

## Modules
- **api**: REST endpoints, SSE, HTTP handlers, Authentication (OIDC/JWT/Static Token).
- **incident**: State machine, transitions, incident lifecycle management, intent-log handling.
- **agent**: SPI interfaces, Detector, Investigator, Diagnoser.
- **policy**: Deterministic safety rules and policy engine. Sole constructor of `ApprovedAction`.
- **k8s**: Fabric8 client wrappers, informers, Kubernetes interactions. Adapters for domain ports.
- **llm**: Prompts, LLM adapter (Groq), parsing JSON-schema model outputs.
- **knowledge**: Static markdown SOPs per agent (loaded into the prompt).
- **approval**: Approval API bounded to proposal_id/hash.
- **audit**: Immutable audit trail generation and storage.

## Ports (Hexagonal Architecture)
The domain core contains pure Java interfaces with no Spring or Fabric8 dependencies. All time is handled via an injected `Clock`.
- `ClusterReader` (owned by k8s): Reads cluster state safely.
- `ClusterWriter` (owned by k8s): Writes changes. Write methods accept ONLY `ApprovedAction`.
- `EvidenceCollector` (owned by agent/k8s): Gathers size-capped, redacted, field-whitelisted evidence.
- `LlmClient` (owned by llm): Talks to Groq with timeouts, rate-limits, backoff, and JSON-schema enforcement.
- `IncidentRepository` (owned by incident): Postgres persistence of incidents, transitions, intents.
- `Clock`: Standard Java `java.time.Clock`.

## Trust Boundaries
- **API -> Core**: All actions from API (approvals) must be authenticated and validated against `proposal_id` and `proposal_hash`.
- **LLM -> Core**: Untrusted. Validated against strict JSON schema. Unknown actions rejected.
- **K8s Logs/Events -> Core**: Untrusted. Redaction is applied, but considered a best-effort defense in depth.
- **Postgres -> Core**: The Postgres connection is a trust boundary.
- **Dashboard Origin -> API**: CORS policies and secure origins for the UI.
- **In-Cluster -> External**: External access only outbound to Groq API.
- **Policy Engine**: Absolute source of truth.

## Agent SPI & Execution
### Detector
Listens to Kubernetes events. Ignores terminations older than a configurable recency window. Resolves hierarchy: Pod -> ReplicaSet -> Deployment. Uses `(namespace, workload_uid)` as the deduplication key.
*Note: Recurrence (second OOM within 24h of RESOLVED) beats cooldown. It creates an incident that goes directly to ESCALATED ("recurrence").*
*Note: Blocked detections (by cooldown, cap, or lock) generate exactly one audit event per workload per cooldown window, and no incident row.*

### Investigator
Gathers evidence. Uses `EvidenceCollector`. Stores size-capped and `redacted_logs`.

### Diagnoser Schema & LLM Adapter
Sends evidence and previous attempts' outcomes to the LLM. 
- The model name must be provided via configuration; startup fails if unset.
- Handles `429 Too Many Requests` and timeouts with exponential backoff.
- For Agent 2 (Rollbacks), the target revision is computed by code (exactly one revision back, verified to exist in `revision_health`). The LLM does *not* choose revisions.

### Type-Level Enforcement
`ApprovedAction` is a `final` class (package-private constructor in `policy`), carrying `proposal_hash` and `expiry`. Creatable ONLY from: a valid proposal, a verified Approval matching `proposal_hash`, and a fresh execution-time policy re-check. `ClusterWriter` strictly accepts only `ApprovedAction`.

### Crash Safety & Intent Log
`remediation_intents.status` = `pending | applied | failed | abandoned`.
Before patching K8s, verify lease is still held and `incident.version` is unchanged. Write `pending` intent. After patching, mark `applied` with `applied_at`.
If execution fails or escalates, the system reconciles with the live cluster state and sets a terminal intent status (`failed` or `abandoned`). Any lease expiry triggers a re-claim and resume from persisted state.

### Verification Criteria (Healthy)
- `IncreaseMemoryLimit`: Pod starts successfully and does not OOM within `stabilityWindow`.
- `RollbackDeployment`: Rollout completes within `rolloutTimeout` (e.g. 5m), `observedGeneration` matches generation, all replicas are Ready, and no new crashes/restarts occur for `stabilityWindow`. Total timeout = sum + margin. No auto-revert on failure (escalate and describe state).

## Incident FSM

The transition table is TOTAL.

| Current State       | Event / Condition | Guard | Next State        | Side Effect | Retry Policy |
|---------------------|-------------------|-------|-------------------|-------------|--------------|
| DETECTED            | Start processing  | Insert OK | INVESTIGATING | DB Insert | None |
| DETECTED            | Insert fail       | DB Error | ESCALATED     | Log | None |
| DETECTED            | Workload deleted  | - | CANCELLED | Log | None |
| INVESTIGATING       | Evidence gathered | DB/k8s success | DIAGNOSING | Store evidence | 3 retries on k8s read fail |
| INVESTIGATING       | k8s read failure  | Max retries hit | ESCALATED | Log | None |
| INVESTIGATING       | Workload deleted  | - | CANCELLED | Log | None |
| INVESTIGATING       | Timeout (e.g. 5m) | - | ESCALATED | Log | None |
| DIAGNOSING          | Fix proposed & rules pass | Valid JSON, Policy OK | AWAITING_APPROVAL | Store proposal | 3 retries on LLM/Groq error |
| DIAGNOSING          | LLM error/Invalid JSON | Max retries hit | ESCALATED | Log | None |
| DIAGNOSING          | Rule denied       | Policy failure | ESCALATED | Log | None |
| DIAGNOSING          | Workload deleted  | - | CANCELLED | Log | None |
| DIAGNOSING          | Timeout (e.g. 5m) | - | ESCALATED | Log | None |
| AWAITING_APPROVAL   | User approved     | Match ID/Hash/Role | REMEDIATING | Create intent log | None |
| AWAITING_APPROVAL   | User rejected     | - | ESCALATED | Log | None |
| AWAITING_APPROVAL   | Timeout (30m)     | - | ESCALATED | Log | None |
| AWAITING_APPROVAL   | Workload deleted  | - | CANCELLED | Log | None |
| REMEDIATING         | Execution re-check denied | Policy failure | ESCALATED | Terminate intent | None |
| REMEDIATING         | ResourceVersion conflict | Max retries hit | ESCALATED | Terminate intent | None |
| REMEDIATING         | ResourceVersion conflict | Attempts < max | INVESTIGATING | Inc attempt, Void approval | None |
| REMEDIATING         | Applied fix       | Patch success | VERIFYING | Mark intent applied | None |
| REMEDIATING         | Resume/Reconcile pending| Matches cluster state| VERIFYING | Sync intent state | None |
| REMEDIATING         | Patch/Dry-run fail| Max retries hit | ESCALATED | Terminate intent | None |
| REMEDIATING         | Workload deleted  | - | CANCELLED | Terminate intent | None |
| REMEDIATING         | Timeout (e.g. 5m) | - | ESCALATED | Terminate intent | None |
| VERIFYING           | Success           | Health criteria met | RESOLVED | Log | None |
| VERIFYING           | Failure           | Criteria failed | REFLECTING | Log | None |
| VERIFYING           | Timeout (sum+margin)| - | REFLECTING | Log | None |
| VERIFYING           | Workload deleted  | - | CANCELLED | Log | None |
| REFLECTING          | Budget available  | Attempts < max | INVESTIGATING | Inc attempt count | None |
| REFLECTING          | Budget exhausted  | Attempts >= max | ESCALATED | Log | None |

*(Note: ESCALATED, RESOLVED, and CANCELLED are terminal states).*

## Concurrency
- **Lease System:** `claimed_by` and `lease_until` fields. Heartbeats renew leases. Lease expiry requires re-claiming. Lock/dedupe key is `(namespace, workload_uid)`.
- **Execution:** Max 4 concurrent incidents handled via Java Virtual Threads.

## Data Model (Postgres)
- **incidents**: `id`, `target_workload`, `namespace`, `workload_uid`, `agent_type`, `type`, `state`, `attempt`, `version`, `claimed_by`, `lease_until`, `created_at`, `updated_at`, `closed_at`, persisted retry counters.
  - *Partial Unique Index:* `(namespace, workload_uid)` WHERE `state NOT IN ('RESOLVED', 'ESCALATED', 'CANCELLED')`.
- **incident_transitions**: `incident_id`, `from_state`, `to_state`, `reason`, `timestamp`. (Append-only via revoked `UPDATE`/`DELETE` for app role).
- **evidence**: `incident_id`, `attempt`, `redacted_logs`, `k8s_events`, `pod_specs`.
- **proposals**: `incident_id`, `attempt`, `proposal_type`, `payload`, `created_at`.
- **approvals**: `incident_id`, `attempt`, `proposal_id`, `proposal_hash`, `approver_identity`, `decision`, `timestamp`, `approved_until`. `UNIQUE(proposal_id)`.
- **policy_decisions**: `incident_id`, `attempt`, `proposal_id`, `rule_id`, `phase` (proposal|execution), `outcome`, `inputs`, `timestamp`.
- **remediation_intents**: `incident_id`, `attempt`, `status` (pending, applied, failed, abandoned), `target_resourceVersion`, `patch_payload`, `applied_at`.
- **revision_health**: Records revision health state. Fed by informer.
- **audit_events**: immutable log. (Append-only).
