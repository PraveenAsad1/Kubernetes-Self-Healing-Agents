package com.safeheal.persistence;

import com.safeheal.incident.Incident;
import com.safeheal.incident.IncidentEvent;
import com.safeheal.incident.IncidentRepository;
import com.safeheal.incident.IncidentState;
import com.safeheal.incident.Transition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcIncidentRepository implements IncidentRepository {

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public JdbcIncidentRepository(JdbcClient jdbcClient) {
        this(jdbcClient, Clock.systemUTC());
    }

    @Autowired
    public JdbcIncidentRepository(JdbcClient jdbcClient, @Autowired(required = false) Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Override
    @Transactional
    public CreateResult create(Incident incident) {
        int rows = jdbcClient.sql("""
            INSERT INTO incidents (id, target_workload, namespace, workload_uid, workload_name, agent_type, type, state, attempt, transient_retries, version, claimed_by, lease_until, created_at, updated_at, closed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (namespace, workload_uid) WHERE state NOT IN ('RESOLVED', 'ESCALATED', 'CANCELLED') DO NOTHING
        """).params(
            incident.id(),
            "target", // placeholder
            incident.namespace(),
            incident.workloadUid(),
            incident.workloadName(),
            incident.agentType(),
            "type", // placeholder
            incident.state().name(),
            incident.attempt(),
            incident.transientRetries(),
            incident.version(),
            incident.claimedBy(),
            incident.leaseUntil() != null ? java.sql.Timestamp.from(incident.leaseUntil()) : null,
            incident.createdAt() != null ? java.sql.Timestamp.from(incident.createdAt()) : null,
            incident.updatedAt() != null ? java.sql.Timestamp.from(incident.updatedAt()) : null,
            incident.closedAt() != null ? java.sql.Timestamp.from(incident.closedAt()) : null
        ).update();

        if (rows == 0) {
            String existingId = jdbcClient.sql("""
                SELECT id FROM incidents
                WHERE namespace = ? AND workload_uid = ? AND state NOT IN ('RESOLVED', 'ESCALATED', 'CANCELLED')
                LIMIT 1
            """).params(incident.namespace(), incident.workloadUid())
            .query(String.class)
            .optional()
            .orElse(null);
            return new CreateResult.Duplicate(existingId);
        }

        return new CreateResult.Created(incident);
    }

    @Override
    public Optional<Incident> findById(String id) {
        return jdbcClient.sql("SELECT * FROM incidents WHERE id = ?").param(id)
            .query(JdbcIncidentRepository::mapIncidentRow)
            .optional();
    }

    @Override
    @Transactional
    public Incident applyTransition(Incident incident, IncidentEvent event, Transition transition) {
        Instant now = clock.instant();
        int rows = jdbcClient.sql("""
            UPDATE incidents SET state = ?, attempt = ?, transient_retries = ?, version = version + 1, updated_at = ?, closed_at = ?
            WHERE id = ? AND version = ?
        """).params(
            transition.nextState().name(),
            incident.attempt(),
            incident.transientRetries(),
            java.sql.Timestamp.from(now),
            transition.nextState().isTerminal() ? java.sql.Timestamp.from(now) : null,
            incident.id(),
            incident.version()
        ).update();

        if (rows == 0) {
            throw new org.springframework.dao.OptimisticLockingFailureException("Stale version");
        }

        jdbcClient.sql("""
            INSERT INTO incident_transitions (incident_id, from_state, to_state, event_type, attempt, timestamp)
            VALUES (?, ?, ?, ?, ?, ?)
        """).params(
            incident.id(),
            incident.state().name(),
            transition.nextState().name(),
            event.getClass().getSimpleName(),
            incident.attempt(),
            java.sql.Timestamp.from(now)
        ).update();

        return findById(incident.id()).orElseThrow();
    }

    @Override
    @Transactional
    public Optional<Incident> claimNext(String workerId, Duration leaseDuration, Instant now) {
        return jdbcClient.sql("""
            UPDATE incidents SET claimed_by = ?, lease_until = ?, version = version + 1
            WHERE id = (
                SELECT id FROM incidents
                WHERE state NOT IN ('RESOLVED', 'ESCALATED', 'CANCELLED', 'AWAITING_APPROVAL')
                  AND (lease_until IS NULL OR lease_until < ?)
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED LIMIT 1
            )
            RETURNING *
        """)
        .params(workerId, java.sql.Timestamp.from(now.plus(leaseDuration)), java.sql.Timestamp.from(now))
        .query(JdbcIncidentRepository::mapIncidentRow)
        .optional();
    }

    @Override
    @Transactional
    public void heartbeat(String id, String workerId, Instant now, Duration leaseDuration) {
        int rows = jdbcClient.sql("UPDATE incidents SET lease_until = ? WHERE id = ? AND claimed_by = ?")
            .params(java.sql.Timestamp.from(now.plus(leaseDuration)), id, workerId)
            .update();
        if (rows == 0) throw new IllegalStateException("Heartbeat failed, not owner");
    }

    @Override
    public List<Incident> findResumable(Instant now) {
        return jdbcClient.sql("""
            SELECT * FROM incidents
            WHERE state NOT IN ('RESOLVED', 'ESCALATED', 'CANCELLED')
              AND lease_until IS NOT NULL
              AND lease_until < ?
            ORDER BY lease_until ASC
        """)
        .param(java.sql.Timestamp.from(now))
        .query(JdbcIncidentRepository::mapIncidentRow)
        .list();
    }

    @Override
    public Instant cooldownUntil(String namespace, String workloadUid) {
        return jdbcClient.sql("SELECT closed_at FROM incidents WHERE namespace = ? AND workload_uid = ? ORDER BY closed_at DESC LIMIT 1")
            .params(namespace, workloadUid)
            .query((rs, rowNum) -> rs.getTimestamp("closed_at") != null ? rs.getTimestamp("closed_at").toInstant() : null)
            .optional().orElse(null);
    }

    @Override
    public int appliedRemediationsSince(Instant since) {
        return jdbcClient.sql("SELECT COUNT(*) FROM remediation_intents WHERE status = 'applied' AND applied_at > ?")
            .param(java.sql.Timestamp.from(since))
            .query(Integer.class)
            .single();
    }

    private static Incident mapIncidentRow(ResultSet rs, int rowNum) throws SQLException {
        return new Incident(
            rs.getString("id"),
            rs.getString("namespace"),
            rs.getString("workload_uid"),
            rs.getString("workload_name"),
            rs.getString("agent_type"),
            IncidentState.valueOf(rs.getString("state")),
            rs.getInt("attempt"),
            rs.getInt("transient_retries"),
            rs.getInt("version"),
            rs.getString("claimed_by"),
            rs.getTimestamp("lease_until") != null ? rs.getTimestamp("lease_until").toInstant() : null,
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null,
            rs.getTimestamp("closed_at") != null ? rs.getTimestamp("closed_at").toInstant() : null
        );
    }
}
