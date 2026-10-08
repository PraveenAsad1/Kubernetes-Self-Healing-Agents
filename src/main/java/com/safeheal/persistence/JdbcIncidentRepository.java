package com.safeheal.persistence;

import com.safeheal.incident.Incident;
import com.safeheal.incident.IncidentRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JdbcIncidentRepository implements IncidentRepository {
    private final JdbcClient jdbcClient;

    public JdbcIncidentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void save(Incident incident) {
        jdbcClient.sql("INSERT INTO incidents (id, namespace, workload_uid, workload_name, agent_type, state, attempt, transient_retries, version, created_at, updated_at) " +
                       "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            .param(incident.id())
            .param(incident.namespace())
            .param(incident.workloadUid())
            .param(incident.workloadName())
            .param(incident.agentType())
            .param(incident.state().name())
            .param(incident.attempt())
            .param(incident.transientRetries())
            .param(incident.version())
            .param(incident.createdAt())
            .param(incident.updatedAt())
            .update();
    }
    
    public Optional<Incident> findById(String id) {
        // Simple stub for now to satisfy basic test compilation
        return Optional.empty();
    }
}
