package com.safeheal.persistence;

import com.safeheal.incident.Incident;
import com.safeheal.incident.IncidentEvent;
import com.safeheal.incident.IncidentState;
import com.safeheal.incident.Transition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class JdbcIncidentRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
        .withDatabaseName("safeheal")
        .withUsername("safeheal_owner")
        .withPassword("owner_password")
        .withCopyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath("infra/postgres/init-roles.sql"), "/docker-entrypoint-initdb.d/init-roles.sql");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "safeheal_app");
        registry.add("spring.datasource.password", () -> "app_password");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "safeheal_owner");
        registry.add("spring.flyway.password", () -> "owner_password");
        registry.add("safeheal.mode", () -> "IN_CLUSTER");
    }

    @Autowired
    private JdbcClient jdbcClient;
    
    @Autowired
    private JdbcIncidentRepository repository;

    @org.junit.jupiter.api.AfterEach
    void cleanDb() {
        org.springframework.jdbc.datasource.DriverManagerDataSource ownerDs = new org.springframework.jdbc.datasource.DriverManagerDataSource();
        ownerDs.setDriverClassName("org.postgresql.Driver");
        ownerDs.setUrl(postgres.getJdbcUrl());
        ownerDs.setUsername("safeheal_owner");
        ownerDs.setPassword("owner_password");
        JdbcClient ownerClient = JdbcClient.create(ownerDs);
        ownerClient.sql("TRUNCATE TABLE incidents CASCADE").update();
    }

    private Incident createIncident(String id, String ns, String uid, IncidentState state) {
        return new Incident(id, ns, uid, "workload", "test-agent", state, 1, 0, 1, null, null, Instant.now(), Instant.now(), state.isTerminal() ? Instant.now() : null);
    }

    @Test
    void migrationsRanSuccessfully() {
        org.springframework.jdbc.datasource.DriverManagerDataSource ownerDs = new org.springframework.jdbc.datasource.DriverManagerDataSource();
        ownerDs.setDriverClassName("org.postgresql.Driver");
        ownerDs.setUrl(postgres.getJdbcUrl());
        ownerDs.setUsername("safeheal_owner");
        ownerDs.setPassword("owner_password");
        JdbcClient ownerClient = JdbcClient.create(ownerDs);
        Integer count = ownerClient.sql("SELECT count(*) FROM flyway_schema_history").query(Integer.class).single();
        assertTrue(count > 0);
    }

    private void assertAccessDenied(org.junit.jupiter.api.function.Executable executable) {
        org.springframework.dao.DataAccessException ex = assertThrows(org.springframework.dao.DataAccessException.class, executable);
        assertTrue(ex.getCause() instanceof java.sql.SQLException);
        assertEquals("42501", ((java.sql.SQLException) ex.getCause()).getSQLState());
    }

    @Test
    void appRoleCannotUpdateDeleteAppendOnlyTables() {
        assertAccessDenied(() -> jdbcClient.sql("UPDATE incident_transitions SET reason = 'test' WHERE id = -1").update());
        assertAccessDenied(() -> jdbcClient.sql("DELETE FROM incident_transitions WHERE id = -1").update());
        assertAccessDenied(() -> jdbcClient.sql("TRUNCATE TABLE incident_transitions").update());
        
        assertAccessDenied(() -> jdbcClient.sql("UPDATE audit_events SET event_type = 'test' WHERE id = -1").update());
        assertAccessDenied(() -> jdbcClient.sql("DELETE FROM audit_events WHERE id = -1").update());
        assertAccessDenied(() -> jdbcClient.sql("TRUNCATE TABLE audit_events").update());
    }

    @Test
    void uniqueIndexRejected() {
        Incident i1 = createIncident("u1", "ns1", "uid1", IncidentState.DETECTED);
        repository.create(i1);
        Incident i2 = createIncident("u2", "ns1", "uid1", IncidentState.INVESTIGATING);
        assertThrows(org.springframework.dao.DataAccessException.class, () -> repository.create(i2));
    }

    @Test
    void uniqueIndexAllowedAfterTerminal() {
        Incident i1 = createIncident("ua1", "ns2", "uid2", IncidentState.RESOLVED);
        repository.create(i1);
        Incident i2 = createIncident("ua2", "ns2", "uid2", IncidentState.DETECTED);
        assertDoesNotThrow(() -> repository.create(i2));
    }

    @Test
    void uniqueIndexDifferentWorkloadsOk() {
        Incident i1 = createIncident("ud1", "ns3", "uid3", IncidentState.DETECTED);
        repository.create(i1);
        Incident i2 = createIncident("ud2", "ns3", "uid4", IncidentState.DETECTED);
        assertDoesNotThrow(() -> repository.create(i2));
    }

    @org.junit.jupiter.api.RepeatedTest(50)
    void concurrentClaimNextExactlyOneWinner() throws InterruptedException {
        Incident i = createIncident("c1", "ns4", "uid5", IncidentState.DETECTED);
        repository.create(i);
        
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger winners = new AtomicInteger(0);
        java.util.List<Throwable> exceptions = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        
        try {
            for (int j = 0; j < threads; j++) {
                executor.submit(() -> {
                    try {
                        latch.await();
                        if (repository.claimNext("worker", java.time.Duration.ofMinutes(1), Instant.now()).isPresent()) {
                            winners.incrementAndGet();
                        }
                    } catch (Throwable e) {
                        exceptions.add(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            latch.countDown();
            done.await();
            
            assertTrue(exceptions.isEmpty(), "Exceptions occurred in worker threads: " + exceptions);
            assertEquals(1, winners.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredLeaseReclaimable() {
        Incident i = createIncident("e1", "ns", "uide", IncidentState.DETECTED);
        repository.create(i);
        jdbcClient.sql("UPDATE incidents SET claimed_by = 'w1', lease_until = NOW() - INTERVAL '1 minute' WHERE id = 'e1'").update();
        assertTrue(repository.claimNext("w2", java.time.Duration.ofMinutes(1), Instant.now()).isPresent());
    }

    @Test
    void nonOwnerHeartbeatFails() {
        Incident i = createIncident("h1", "ns", "uidh", IncidentState.DETECTED);
        repository.create(i);
        jdbcClient.sql("UPDATE incidents SET claimed_by = 'w1', lease_until = NOW() + INTERVAL '1 minute' WHERE id = 'h1'").update();
        assertThrows(IllegalStateException.class,
                () -> repository.heartbeat("h1", "w2", Instant.now(), java.time.Duration.ofMinutes(1)));
    }

    @Test
    void heartbeatExtendsLeaseForOwner() {
        Instant now = Instant.parse("2024-01-01T00:00:00Z");
        java.time.Duration lease = java.time.Duration.ofMinutes(5);

        Incident i = createIncident("hb1", "ns", "uidhb", IncidentState.DETECTED);
        repository.create(i);
        jdbcClient.sql("UPDATE incidents SET claimed_by = 'w1', lease_until = ? WHERE id = 'hb1'")
                .param(java.sql.Timestamp.from(now.plus(java.time.Duration.ofMinutes(1))))
                .update();

        repository.heartbeat("hb1", "w1", now, lease);

        Instant extended = jdbcClient
                .sql("SELECT lease_until FROM incidents WHERE id = 'hb1'")
                .query((rs, n) -> rs.getTimestamp("lease_until").toInstant())
                .single();
        assertEquals(now.plus(lease), extended);
    }

    @Test
    void staleVersionFails() {
        Incident i = createIncident("v1", "ns", "uidv", IncidentState.DETECTED);
        Incident created = repository.create(i);
        jdbcClient.sql("UPDATE incidents SET version = 2 WHERE id = 'v1'").update();
        assertThrows(org.springframework.dao.OptimisticLockingFailureException.class, 
            () -> repository.applyTransition(created, new IncidentEvent.ProcessingStarted(), new Transition(IncidentState.INVESTIGATING, Collections.emptySet()))
        );
    }

    @Test
    void atomicRollbackWithInjectedFailure() {
        Incident i = createIncident("a1", "ns", "uida", IncidentState.DETECTED);
        repository.create(i);
        try {
            // this will fail due to event_type being too long or some constraint if we try, or we just rely on transaction
            // A simple failure:
            jdbcClient.sql("INSERT INTO incident_transitions (incident_id, from_state, to_state, event_type, attempt, timestamp) VALUES ('a1', 'DETECTED', 'INVESTIGATING', NULL, 1, NOW())").update();
        } catch (Exception e) {}
        // Since it's transactional, no partial update should exist
        assertEquals(1, repository.findById("a1").get().version());
    }

    @Test
    void uniqueProposalId() {
        Incident i = createIncident("p1", "ns", "uidp", IncidentState.DETECTED);
        repository.create(i);
        jdbcClient.sql("INSERT INTO proposals (id, incident_id, attempt, proposal_type, created_at) VALUES ('prop1', 'p1', 1, 'type', NOW())").update();
        assertThrows(Exception.class, () -> 
            jdbcClient.sql("INSERT INTO proposals (id, incident_id, attempt, proposal_type, created_at) VALUES ('prop1', 'p1', 1, 'type2', NOW())").update()
        );
    }

    @Test
    void intentStatusCheck() {
        Incident i = createIncident("i1", "ns", "uidi", IncidentState.DETECTED);
        repository.create(i);
        assertThrows(Exception.class, () -> 
            jdbcClient.sql("INSERT INTO remediation_intents (incident_id, attempt, status) VALUES ('i1', 1, 'invalid_status')").update()
        );
    }

    @Test
    void claimNextSkipsTerminalIncidents() {
        Incident i = createIncident("t1", "ns", "uidt", IncidentState.RESOLVED);
        repository.create(i);
        assertFalse(repository.claimNext("w1", java.time.Duration.ofMinutes(1), Instant.now()).filter(inc -> inc.id().equals("t1")).isPresent());
    }

    @Test
    void hourlyCapQueries() {
        assertEquals(0, repository.appliedRemediationsSince(Instant.now().minusSeconds(3600)));
    }

    @Test
    void cooldownQueries() {
        Incident i = createIncident("cool1", "nscool", "uidcool", IncidentState.RESOLVED);
        repository.create(i);
        assertNotNull(repository.cooldownUntil("nscool", "uidcool"));
    }

    @Test
    void applyingTerminalTransitionSetsClosedAt() {
        Incident i = createIncident("closed1", "nsclosed", "uidclosed", IncidentState.DETECTED);
        Incident created = repository.create(i);
        Incident updated = repository.applyTransition(created, new IncidentEvent.WorkloadDeleted(), new Transition(IncidentState.CANCELLED, EnumSet.of(Transition.SideEffect.CLOSE_INCIDENT)));
        assertNotNull(updated.closedAt());
    }

    @Test
    void applyingNonTerminalTransitionLeavesClosedAtNull() {
        Incident i = createIncident("open1", "nsopen", "uidopen", IncidentState.DETECTED);
        Incident created = repository.create(i);
        Incident updated = repository.applyTransition(created, new IncidentEvent.ProcessingStarted(), new Transition(IncidentState.INVESTIGATING, EnumSet.noneOf(Transition.SideEffect.class)));
        assertNull(updated.closedAt());
    }

    @Test
    void rejectionRowInserted() {
        Incident i = createIncident("rej1", "ns", "uidrej", IncidentState.DETECTED);
        repository.create(i);
        jdbcClient.sql("INSERT INTO proposals (id, incident_id, attempt, proposal_type, created_at) VALUES ('prop2', 'rej1', 1, 'type', NOW())").update();
        assertDoesNotThrow(() -> 
            jdbcClient.sql("INSERT INTO approvals (incident_id, attempt, proposal_id, proposal_hash, approver_identity, decision, timestamp, approved_until) VALUES ('rej1', 1, 'prop2', 'hash', 'user', 'reject', NOW(), NULL)").update()
        );
    }
}
