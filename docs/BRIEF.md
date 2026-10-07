# SafeHeal brief (v0, defaults chosen; editable)

## Goal
Detect specific Kubernetes failures, diagnose with an LLM, propose a typed fix, gate it with deterministic
policy and human approval, apply it safely, verify recovery, and keep a full audit trail.

## Agents
- Agent 1 (build first): container with lastState.terminated.reason == OOMKilled (exit 137), owned by a
  Deployment, in an allowlisted namespace. Action: IncreaseMemoryLimit.
- Agent 2 (after Agent 1 works end to end): crash loop, NOT OOM, shortly after a rollout, where the previous
  revision was healthy. Action: RollbackDeployment (one revision back only).
- Triage: exit 137 without reason OOMKilled is not an OOM incident. Repeated restarts of one crash are ONE incident.
  Deployments only (no Jobs, CronJobs, bare pods).

## Incident lifecycle
DETECTED -> INVESTIGATING -> DIAGNOSING -> PROPOSING -> POLICY_CHECK -> AWAITING_APPROVAL -> REMEDIATING
-> VERIFYING -> RESOLVED. VERIFYING failure -> REFLECTING -> INVESTIGATING (budget-limited).
ESCALATED (terminal) on: policy denial, approval rejected or timed out, retry budget exhausted, invalid LLM output after retries.

## Safety defaults (all configurable)
- OOM action only increases a memory limit; never decreases; request never exceeds limit.
- Max 2x increase per attempt; absolute ceiling 2Gi.
- Namespace allowlist; hard denylist: kube-system, kube-public, kube-node-lease.
- Max 3 attempts per incident; 30 min cooldown per workload; max 5 remediations/hour cluster-wide.
- Rollback: only to a revision that was previously healthy, one revision back, must follow a recent rollout.
- Approval timeout 30 min -> ESCALATED. Every policy decision logs the rule id that fired.

## Architecture
Single-replica agent running in-cluster: Detector (pod informer) -> Triage router -> Agent plugin (SPI) ->
Investigator -> LLM adapter -> Policy engine -> Approval gate -> Remediator -> Verifier, all driven by a persisted
Incident FSM. Postgres stores incidents, incident_transitions (append-only), evidence, proposals, approvals,
audit_events. API: incident reads, approve/reject, SSE live updates. Dashboard (React) comes last.

## Testing
Unit and table-driven tests for FSM and policy. Testcontainers Postgres and k3s for real crash tests.
WireMock for Groq in CI (never the real API). A malicious-log-line prompt-injection test. Auth tests.

## Environments
Local: kind (cluster safeheal-dev). CI: kind or k3s in GitHub Actions. Demo: k3s on a cloud VM via Helm.

## Non-goals (v1)
Auto-approval, multi-cluster, multi-replica, other failure types, fixing app bugs, right-sizing, node problems,
Slack or pager integrations.