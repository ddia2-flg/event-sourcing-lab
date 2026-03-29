package org.borysovski.eventsourcing.eventstore;

import org.borysovski.eventsourcing.domain.event.*;
import org.borysovski.eventsourcing.domain.exception.OptimisticLockingException;
import org.borysovski.eventsourcing.infrastructure.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@JdbcTest
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresEventStore.class, JacksonEventSerializer.class, JacksonConfig.class})
@DisplayName("PostgresEventStore")
class PostgresEventStoreTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("eventstore_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PostgresEventStore eventStore;

    @BeforeEach
    void createSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS account_events (
                    id           BIGSERIAL    PRIMARY KEY,
                    aggregate_id VARCHAR(36)  NOT NULL,
                    version      BIGINT       NOT NULL,
                    event_type   VARCHAR(100) NOT NULL,
                    payload      JSONB        NOT NULL,
                    metadata     JSONB,
                    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
                    CONSTRAINT uq_aggregate_version UNIQUE (aggregate_id, version)
                )
                """);
    }

    private static final String ACCOUNT_ID = "acc-test-001";
    private static final Instant NOW = Instant.now();

    private List<DomainEvent> sampleEvents() {
        return List.of(
                new AccountOpened(ACCOUNT_ID, "Alice", new BigDecimal("1000"), NOW),
                new MoneyDeposited(ACCOUNT_ID, new BigDecimal("500"), "salary", NOW),
                new MoneyWithdrawn(ACCOUNT_ID, new BigDecimal("200"), "rent", NOW)
        );
    }

    @Test
    @DisplayName("append and load returns events in version order")
    void appendAndLoad() {
        eventStore.append(ACCOUNT_ID, 0, sampleEvents());

        List<DomainEvent> loaded = eventStore.load(ACCOUNT_ID);

        assertThat(loaded).hasSize(3);
        assertThat(loaded.get(0)).isInstanceOf(AccountOpened.class);
        assertThat(loaded.get(1)).isInstanceOf(MoneyDeposited.class);
        assertThat(loaded.get(2)).isInstanceOf(MoneyWithdrawn.class);
    }

    @Test
    @DisplayName("load returns empty list for unknown aggregate")
    void loadUnknownAggregate() {
        List<DomainEvent> events = eventStore.load("does-not-exist");
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("append with wrong expectedVersion throws OptimisticLockingException")
    void optimisticLockingViolation() {
        eventStore.append(ACCOUNT_ID, 0, List.of(
                new AccountOpened(ACCOUNT_ID, "Alice", BigDecimal.TEN, NOW)));

        // Trying to append at version 0 again — should conflict on version 1
        assertThatThrownBy(() ->
                eventStore.append(ACCOUNT_ID, 0, List.of(
                        new MoneyDeposited(ACCOUNT_ID, BigDecimal.ONE, "dup", NOW))))
                .isInstanceOf(OptimisticLockingException.class)
                .hasMessageContaining(ACCOUNT_ID);
    }

    @Test
    @DisplayName("loadUpTo returns only events at or before given timestamp")
    void loadUpTo() {
        Instant past   = Instant.parse("2026-01-01T10:00:00Z");
        Instant middle = Instant.parse("2026-01-01T12:00:00Z");
        Instant future = Instant.parse("2026-01-01T14:00:00Z");

        // Insert manually with controlled timestamps
        jdbc.update("""
                INSERT INTO account_events (aggregate_id, version, event_type, payload, occurred_at)
                VALUES (?, 1, 'AccountOpened', ?::jsonb, ?)
                """, ACCOUNT_ID, "{\"aggregateId\":\"acc-test-001\",\"owner\":\"Alice\",\"initialBalance\":1000,\"occurredAt\":\"2026-01-01T10:00:00Z\"}", past);

        jdbc.update("""
                INSERT INTO account_events (aggregate_id, version, event_type, payload, occurred_at)
                VALUES (?, 2, 'MoneyDeposited', ?::jsonb, ?)
                """, ACCOUNT_ID, "{\"aggregateId\":\"acc-test-001\",\"amount\":500,\"description\":\"salary\",\"occurredAt\":\"2026-01-01T14:00:00Z\"}", future);

        List<DomainEvent> events = eventStore.loadUpTo(ACCOUNT_ID, middle);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(AccountOpened.class);
    }

    @Test
    @DisplayName("loadFrom returns events starting at given version inclusive")
    void loadFrom() {
        eventStore.append(ACCOUNT_ID, 0, sampleEvents());

        List<DomainEvent> fromV2 = eventStore.loadFrom(ACCOUNT_ID, 2);

        assertThat(fromV2).hasSize(2);
        assertThat(fromV2.get(0)).isInstanceOf(MoneyDeposited.class);
        assertThat(fromV2.get(1)).isInstanceOf(MoneyWithdrawn.class);
    }

    @Test
    @DisplayName("appending events in two batches preserves correct versions")
    void appendInTwoBatches() {
        eventStore.append(ACCOUNT_ID, 0, List.of(
                new AccountOpened(ACCOUNT_ID, "Alice", new BigDecimal("1000"), NOW)));

        eventStore.append(ACCOUNT_ID, 1, List.of(
                new MoneyDeposited(ACCOUNT_ID, new BigDecimal("200"), "bonus", NOW)));

        List<DomainEvent> all = eventStore.load(ACCOUNT_ID);
        assertThat(all).hasSize(2);
    }
}
