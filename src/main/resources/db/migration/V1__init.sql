CREATE TABLE incidents (
    id VARCHAR(255) PRIMARY KEY,
    target_workload VARCHAR(255),
    namespace VARCHAR(255) NOT NULL,
    workload_uid VARCHAR(255) NOT NULL,
    workload_name VARCHAR(255) NOT NULL,
    agent_type VARCHAR(50) NOT NULL,
    type VARCHAR(50),
    state VARCHAR(50) NOT NULL,
    attempt INT NOT NULL DEFAULT 1,
    transient_retries INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 1,
    claimed_by VARCHAR(255),
    lease_until TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    closed_at TIMESTAMP
);

CREATE UNIQUE INDEX idx_incidents_active 
ON incidents (namespace, workload_uid) 
WHERE state NOT IN ('RESOLVED', 'ESCALATED', 'CANCELLED');

CREATE TABLE incident_transitions (
    id SERIAL PRIMARY KEY,
    incident_id VARCHAR(255) REFERENCES incidents(id),
    from_state VARCHAR(50) NOT NULL,
    to_state VARCHAR(50) NOT NULL,
    reason TEXT,
    timestamp TIMESTAMP NOT NULL
);

CREATE TABLE evidence (
    id SERIAL PRIMARY KEY,
    incident_id VARCHAR(255) REFERENCES incidents(id),
    attempt INT NOT NULL,
    redacted_logs TEXT,
    k8s_events TEXT,
    pod_specs TEXT
);

CREATE TABLE proposals (
    id VARCHAR(255) PRIMARY KEY,
    incident_id VARCHAR(255) REFERENCES incidents(id),
    attempt INT NOT NULL,
    proposal_type VARCHAR(50) NOT NULL,
    payload TEXT,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE approvals (
    id SERIAL PRIMARY KEY,
    incident_id VARCHAR(255) REFERENCES incidents(id),
    attempt INT NOT NULL,
    proposal_id VARCHAR(255) REFERENCES proposals(id) UNIQUE,
    proposal_hash VARCHAR(255) NOT NULL,
    approver_identity VARCHAR(255) NOT NULL,
    decision VARCHAR(50) NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    approved_until TIMESTAMP NOT NULL
);

CREATE TABLE policy_decisions (
    id SERIAL PRIMARY KEY,
    incident_id VARCHAR(255) REFERENCES incidents(id),
    attempt INT NOT NULL,
    proposal_id VARCHAR(255) REFERENCES proposals(id),
    rule_id VARCHAR(255) NOT NULL,
    phase VARCHAR(50) NOT NULL,
    outcome VARCHAR(50) NOT NULL,
    inputs TEXT,
    timestamp TIMESTAMP NOT NULL
);

CREATE TABLE remediation_intents (
    id SERIAL PRIMARY KEY,
    incident_id VARCHAR(255) REFERENCES incidents(id),
    attempt INT NOT NULL,
    status VARCHAR(50) NOT NULL CHECK (status IN ('pending', 'applied', 'failed', 'abandoned')),
    target_resource_version VARCHAR(255),
    patch_payload TEXT,
    applied_at TIMESTAMP
);

CREATE TABLE audit_events (
    id SERIAL PRIMARY KEY,
    incident_id VARCHAR(255) REFERENCES incidents(id),
    event_type VARCHAR(50) NOT NULL,
    payload TEXT,
    timestamp TIMESTAMP NOT NULL
);

GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO safeheal_app;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO safeheal_app;

REVOKE UPDATE, DELETE ON incident_transitions FROM safeheal_app;
REVOKE UPDATE, DELETE ON audit_events FROM safeheal_app;
