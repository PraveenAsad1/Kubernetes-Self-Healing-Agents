ALTER TABLE incidents DROP CONSTRAINT incidents_agent_type_check;
ALTER TABLE incidents ADD CONSTRAINT incidents_agent_type_check CHECK (agent_type ~ '^[a-z][a-z0-9-]{0,49}$');

-- Explicit grants to safeheal_app per ADR 20
GRANT SELECT, INSERT, UPDATE ON incidents TO safeheal_app;
