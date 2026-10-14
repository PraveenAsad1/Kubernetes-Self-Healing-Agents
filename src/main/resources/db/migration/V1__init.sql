REVOKE CREATE ON SCHEMA public FROM public;

CREATE TABLE incidents (
    id VARCHAR(255) PRIMARY KEY,
    target_workload VARCHAR(255),
    namespace VARCHAR(255) NOT NULL,
    workload_uid VARCHAR(255) NOT NULL,
    workload_name VARCHAR(255) NOT NULL,
    agent_type VARCHAR(50) NOT NULL CHECK (agent_type IN ('test-agent', 'deployment-healer', 'statefulset-healer', 'daemonset-healer')),
    type VARCHAR(50),
    state VARCHAR(50) NOT NULL CHECK (state IN ('DETECTED', 'INVESTIGATING', 'DIAGNOSING', 'AWAITING_APPROVAL', 'REMEDIATING', 'VERIFYING', 'REFLECTING', 'RESOLVED', 'ESCALATED', 'CANCELLED')),
    attempt INT NOT NULL DEFAULT 1,
    transient_retries INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 1,
    claimed_by VARCHAR(255),
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    CONSTRAINT check_closed_at CHECK ((closed_at IS NOT NULL) = (state IN ('RESOLVED', 'ESCALATED', 'CANCELLED')))
);

CREATE UNIQUE INDEX idx_incidents_active 
ON incidents (namespace, workload_uid) 
WHERE state NOT IN ('RESOLVED', 'ESCALATED', 'CANCELLED');

CREATE INDEX idx_incidents_claim ON incidents (state, lease_until);
CREATE INDEX idx_incidents_cooldown ON incidents (namespace, workload_uid, closed_at);

CREATE TABLE incident_transitions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id VARCHAR(255) REFERENCES incidents(id),
    from_state VARCHAR(50) NOT NULL,
    to_state VARCHAR(50) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    attempt INT NOT NULL,
    reason TEXT,
    timestamp TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_transitions_incident_id ON incident_transitions(incident_id);

CREATE TABLE evidence (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id VARCHAR(255) REFERENCES incidents(id),
    attempt INT NOT NULL,
    redacted_logs TEXT,
    k8s_events TEXT,
    pod_specs TEXT,
    CONSTRAINT check_evidence_log_length CHECK (LENGTH(redacted_logs) <= 51200 OR redacted_logs IS NULL)
);
CREATE INDEX idx_evidence_incident_id ON evidence(incident_id);

CREATE TABLE proposals (
    id VARCHAR(255) PRIMARY KEY,
    incident_id VARCHAR(255) REFERENCES incidents(id),
    attempt INT NOT NULL,
    proposal_type VARCHAR(50) NOT NULL,
    payload TEXT,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_proposals_incident_id ON proposals(incident_id);

CREATE TABLE approvals (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id VARCHAR(255) REFERENCES incidents(id),
    attempt INT NOT NULL,
    proposal_id VARCHAR(255) REFERENCES proposals(id) UNIQUE,
    proposal_hash VARCHAR(255) NOT NULL,
    approver_identity VARCHAR(255) NOT NULL,
    decision VARCHAR(50) NOT NULL CHECK (decision IN ('approve', 'reject')),
    timestamp TIMESTAMPTZ NOT NULL,
    approved_until TIMESTAMPTZ,
    CONSTRAINT check_approved_until CHECK (decision <> 'approve' OR approved_until IS NOT NULL)
);
CREATE INDEX idx_approvals_incident_id ON approvals(incident_id);

CREATE TABLE policy_decisions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id VARCHAR(255) REFERENCES incidents(id),
    attempt INT NOT NULL,
    proposal_id VARCHAR(255) REFERENCES proposals(id),
    rule_id VARCHAR(255) NOT NULL,
    phase VARCHAR(50) NOT NULL CHECK (phase IN ('DIAGNOSIS', 'EXECUTION')),
    outcome VARCHAR(50) NOT NULL CHECK (outcome IN ('ALLOW', 'DENY', 'WARN')),
    inputs TEXT,
    timestamp TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_policy_decisions_incident_id ON policy_decisions(incident_id);

CREATE TABLE remediation_intents (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id VARCHAR(255) REFERENCES incidents(id),
    attempt INT NOT NULL,
    status VARCHAR(50) NOT NULL CHECK (status IN ('pending', 'applied', 'failed', 'abandoned')),
    target_resource_version VARCHAR(255),
    patch_payload TEXT,
    applied_at TIMESTAMPTZ
);
CREATE INDEX idx_intents_incident_id ON remediation_intents(incident_id);
CREATE INDEX idx_intents_hourly_cap ON remediation_intents (status, applied_at);

CREATE TABLE audit_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id VARCHAR(255) REFERENCES incidents(id),
    event_type VARCHAR(50) NOT NULL,
    payload TEXT,
    timestamp TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_audit_incident_id ON audit_events(incident_id);


-- Revoke all by default for safeheal_app
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM safeheal_app;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM safeheal_app;
REVOKE CREATE ON SCHEMA public FROM safeheal_app;

-- Least privilege
GRANT SELECT, INSERT, UPDATE ON incidents, remediation_intents TO safeheal_app;
GRANT SELECT, INSERT ON incident_transitions, audit_events, evidence, proposals, approvals, policy_decisions TO safeheal_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO safeheal_app;
