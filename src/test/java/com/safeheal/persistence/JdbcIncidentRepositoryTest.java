package com.safeheal.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

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
        // Connecting as app user for the app, flyway uses owner
        registry.add("spring.datasource.username", () -> "safeheal_app");
        registry.add("spring.datasource.password", () -> "app_password");
        
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "safeheal_owner");
        registry.add("spring.flyway.password", () -> "owner_password");
        registry.add("safeheal.mode", () -> "IN_CLUSTER");
    }

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void migrationsRanSuccessfully() {
        Integer count = jdbcClient.sql("SELECT count(*) FROM flyway_schema_history").query(Integer.class).single();
        assertTrue(count > 0);
    }

    @Test
    void appRoleCannotUpdateOrDeleteAppendOnlyTables() {
        // Assert that the app role doesn't have UPDATE permissions on incident_transitions
        assertThrows(org.springframework.dao.DataAccessException.class, 
            () -> jdbcClient.sql("UPDATE incident_transitions SET reason = 'test' WHERE id = -1").update()
        );

        // Assert that the app role doesn't have DELETE permissions on incident_transitions
        assertThrows(org.springframework.dao.DataAccessException.class, 
            () -> jdbcClient.sql("DELETE FROM incident_transitions WHERE id = -1").update()
        );

        // Assert that the app role doesn't have UPDATE permissions on audit_events
        assertThrows(org.springframework.dao.DataAccessException.class, 
            () -> jdbcClient.sql("UPDATE audit_events SET payload = 'test' WHERE id = -1").update()
        );

        // Assert that the app role doesn't have DELETE permissions on audit_events
        assertThrows(org.springframework.dao.DataAccessException.class, 
            () -> jdbcClient.sql("DELETE FROM audit_events WHERE id = -1").update()
        );
    }
}
