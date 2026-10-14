# AGENTS.md: SafeHeal

SafeHeal is a policy-gated Kubernetes self-healing agent platform, built in Java from scratch.
There is no legacy code to consult or translate. Rule numbers are stable references; never renumber.

## Sources of truth (read before every phase)
- docs/BRIEF.md: goals, scope, safety defaults, non-goals
- docs/ARCHITECTURE.md: modules, ports, trust boundaries, FSM, data model
- docs/DECISIONS.md: ADRs and assumptions (record every new non-obvious choice here)
- docs/FSM.md: GENERATED from code via ./gradlew generateFsmDocs; never hand-edit
If unclear, record the assumption in docs/DECISIONS.md and proceed. Never invent undocumented behavior silently.

## Stack (fixed; do not substitute or downgrade)
- Java 25 for ALL application and test source. No Kotlin sources. No Lombok.
- Gradle 9.1+ wrapper (Java 25 requires it). Build scripts are build.gradle.kts / settings.gradle.kts only.
- Spring Boot: latest 4.0.x GA patch (never milestones or RCs). Use Boot 4's modular starters; verify names, don't guess.
- fabric8 kubernetes-client; plain RestClient for Groq (OpenAI-compatible, JSON-schema output); Resilience4j;
  Postgres 17 + Flyway + JdbcClient; Spring Security (OIDC/JWT); Micrometer + OpenTelemetry.
- Tests: JUnit 5, Testcontainers (current 2.x artifacts), WireMock, latest ArchUnit 1.4.x. Delivery: Jib, Helm, GitHub Actions.
- Do not change the language, JDK or Spring Boot major version, and add no dependency without recording why in DECISIONS.md.
- Local environment: kind cluster "safeheal-dev" (context kind-safeheal-dev). Docker must be running for tests.

## Safety and architecture rules (non-negotiable)
1. The LLM only produces a typed ActionProposal (sealed interface). It never executes or emits commands.
   Unparseable or unknown output is rejected and logged.
2. The policy engine is deterministic, has zero LLM dependency, and runs before every cluster write.
3. No cluster write without a persisted, authenticated human approval bound to that exact proposal (proposal_id + proposal_hash).
4. Every FSM transition is persisted before its side effects. Incidents resume after restart. All writes are idempotent.
5. Pod data and logs are untrusted. Redact secrets and cap size before any LLM call or storage. Redaction is best-effort defense in depth.
6. Patches: server-side dry-run first, then optimistic locking on resourceVersion.
7. Hexagonal layout, package-by-feature. incident/ and agent/ have no Spring, fabric8 or infrastructure imports (ArchUnit-enforced).
8. Every phase ships with tests. No TODOs, no placeholder implementations, no mocks in main code.
9. Constructor injection only; records and sealed types for the domain.
10. After each phase, produce a walkthrough artifact (what was built, tested, known gaps). Do not commit walkthrough or plan files.
11. Failure classification (triage) is deterministic code, never the LLM.
12. One open incident per workload at a time, enforced by the database (partial unique index), never only in memory.
13. Adding an agent must require zero changes to the FSM, policy framework, approval or audit modules.
    If it does, stop and report the abstraction flaw.
14. Runs in-cluster via Helm with minimal RBAC; the same chart works on kind, k3s and managed clusters.
    Secrets only via Kubernetes Secrets. No unauthenticated write endpoints.
15. Git: work on the phase branch I name. Commit after each logical unit using Conventional Commits, small and focused.
    Never force-push, never rewrite pushed history, never commit secrets, kubeconfig or .env. Do not merge to main; I merge and tag.
16. On startup, refuse to run unless the active kubeconfig context is in the configured allowlist (default: kind-safeheal-dev).
    The guard applies in KUBECONFIG mode only; IN_CLUSTER mode skips it.

## Reporting and discipline
17. Report only results produced by commands (paste the output). Never type test names, counts, or pass rates from memory.
18. Never modify a test to make it pass. If a test is wrong, explain why and show that diff separately.
19. No helper scripts, downloaded distributions, or generated artifacts left in the repo. Never download tooling into the working tree.
20. Never commit on a red build. Finish every phase with a clean git status and the git log.
21. Plan artifact first. If a phase prompt conflicts with docs/ARCHITECTURE.md or DECISIONS.md, stop and report the conflict.

## Code and test standards
22. Language: all source and tests are Java. If a tool or template generates Kotlin or Groovy sources, convert or delete them.
23. FSM: transitions are DATA (declarative rule list), not nested conditionals. The rule list and every side-effect set are immutable.
    Guards read incident state and FsmConfig; callers never supply budget or limit decisions. decide() automatically adds CLOSE_INCIDENT for
    terminal targets, and docs/FSM.md renders these EFFECTIVE effects through the same function.
24. Time: all time goes through an injected java.time.Clock (and Instant parameters at repository boundaries). No Instant.now(), NOW() or sleeps in logic or tests.
25. Tests must be able to fail:
    - No catch-and-ignore, no tolerance fudges, no assertions that accept any exception (assert the specific type or SQLState).
    - Golden tables are hand-written literal data, never generated from or read from the code under test.
    - Every architecture rule needs a negative test that proves it fails on a violating fixture.
    - Concurrency tests use real threads, a start latch, collected Throwables, and @RepeatedTest. Never rely on re-running Gradle (up-to-date checks skip it).
    - One behavior per test method, descriptive names. Never silently skip Docker-dependent tests.
26. Persistence: all timestamps are TIMESTAMPTZ; CHECK constraints on enum-like columns; least-privilege grants to safeheal_app
    (no DELETE, TRUNCATE, TRIGGER or REFERENCES; append-only tables are SELECT and INSERT only). Roles are created by the init script, never in
    migrations, and no passwords or secrets appear in migrations or main sources. Repository tests connect as safeheal_app; migrations run as the owner.
    Every new migration grants explicitly. Once a phase is tagged, existing migrations are immutable; add a new Vn file instead.
27. Claims and writes are atomic: one statement, or one explicit transaction. No SELECT-then-UPDATE across autocommit statements.
28. Do not call fabric8 or Spring types from domain code; do not let fabric8 types leave the k8s package.
29. Verify versions and artifact names against official sources (Spring Boot BOM, Maven Central, release pages). Do not guess names or versions.

## Phase workflow
- One phase at a time. Branch: phase/N-short-name. Do not start the next phase.
- Order: read the sources of truth, produce the plan artifact, wait for confirmation if the prompt says so, implement, test, commit.
- Definition of done: ./gradlew clean test --rerun-tasks is green with no flags; git status is clean; git log shows small Conventional Commits;
  docs and FSM.md are regenerated and committed; the final report contains pasted command output (per-suite counts and the real test-name list from
  build/test-results XML) and known gaps.