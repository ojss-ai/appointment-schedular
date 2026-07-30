// TASK: ATOM-KAFKA-001 (AC: V012–V014 apply; dedup unique constraint;
// audit_writer INSERT-only enforced at DB layer)
package com.scheduler.api.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the Phase 3 migrations (renumbered V012 outbox, V013
 * processed_events, V014 audit_log — see migration header comments) and the
 * HIPAA append-only guarantee: audit_writer can INSERT but neither UPDATE nor
 * DELETE audit_log rows.
 */
@SpringBootTest
@Testcontainers
class EventMigrationsIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("scheduler_test")
        .withUsername("scheduler")
        .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("app.jwt.secret", () -> "integration-test-secret-key-32+bytes-long!!");
        r.add("app.jwt.expiry-hours", () -> 1);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DataSource dataSource;

    @Test
    void phase3Tables_exist() {
        for (String table : new String[] {"outbox", "processed_events", "audit_log"}) {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_schema = 'public' AND table_name = ?",
                Integer.class, table);
            assertThat(count).as("table %s must exist", table).isEqualTo(1);
        }
        Boolean rls = jdbcTemplate.queryForObject(
            "SELECT relrowsecurity FROM pg_class WHERE relname = 'audit_log'", Boolean.class);
        assertThat(rls).as("RLS enabled on audit_log").isTrue();
    }

    @Test
    void processedEvents_rejectsDuplicateConsumerGroupAndMessageKey() {
        String key = UUID.randomUUID().toString();
        String insert = "INSERT INTO processed_events "
            + "(consumer_group, message_key, topic, partition, offset_value) "
            + "VALUES ('notification-consumers', ?, 'tenant.bookings.lifecycle', 0, 1)";
        jdbcTemplate.update(insert, key);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, key))
            .as("UNIQUE (consumer_group, message_key) must reject duplicates (NFR-2.1)")
            .hasMessageContaining("duplicate key");
    }

    @Test
    void auditWriter_canInsert_butNeverUpdateOrDelete() throws SQLException {
        UUID auditId = UUID.randomUUID();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SET ROLE audit_writer");
            // INSERT allowed
            stmt.execute(("INSERT INTO audit_log (id, tenant_id, booking_id, user_id, "
                + "event_type, new_status, occurred_at) VALUES ('%s', '%s', '%s', '%s', "
                + "'BookingConfirmed', 'CONFIRMED', now())")
                .formatted(auditId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

            // UPDATE denied
            assertThatThrownBy(() -> stmt.execute(
                "UPDATE audit_log SET new_status = 'TAMPERED' WHERE id = '" + auditId + "'"))
                .hasMessageContaining("permission denied");

            // DELETE denied
            assertThatThrownBy(() -> stmt.execute(
                "DELETE FROM audit_log WHERE id = '" + auditId + "'"))
                .hasMessageContaining("permission denied");

            stmt.execute("RESET ROLE");
        }
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_log WHERE id = ?", Integer.class, auditId);
        assertThat(count).isEqualTo(1);
    }
}
