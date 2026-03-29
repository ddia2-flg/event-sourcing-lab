package org.borysovski.eventsourcing.eventstore;

import org.borysovski.eventsourcing.domain.event.DomainEvent;
import org.borysovski.eventsourcing.domain.exception.OptimisticLockingException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class PostgresEventStore implements EventStore {

    private final JdbcTemplate jdbc;
    private final JacksonEventSerializer serializer;

    public PostgresEventStore(JdbcTemplate jdbc, JacksonEventSerializer serializer) {
        this.jdbc = jdbc;
        this.serializer = serializer;
    }

    @Override
    @Transactional
    public void append(String aggregateId, long expectedVersion, List<DomainEvent> events) {
        long nextVersion = expectedVersion + 1;
        for (DomainEvent event : events) {
            try {
                jdbc.update("""
                        INSERT INTO account_events (aggregate_id, version, event_type, payload)
                        VALUES (?, ?, ?, ?::jsonb)
                        """,
                        aggregateId,
                        nextVersion++,
                        JacksonEventSerializer.eventTypeFor(event),
                        serializer.serialize(event));
            } catch (DuplicateKeyException e) {
                throw new OptimisticLockingException(aggregateId);
            }
        }
    }

    @Override
    public List<DomainEvent> load(String aggregateId) {
        return jdbc.query("""
                SELECT id, aggregate_id, version, event_type, payload::text, occurred_at
                FROM account_events
                WHERE aggregate_id = ?
                ORDER BY version ASC
                """,
                this::mapRow,
                aggregateId);
    }

    @Override
    public List<DomainEvent> loadUpTo(String aggregateId, Instant asOf) {
        return jdbc.query("""
                SELECT id, aggregate_id, version, event_type, payload::text, occurred_at
                FROM account_events
                WHERE aggregate_id = ? AND occurred_at <= ?
                ORDER BY version ASC
                """,
                this::mapRow,
                aggregateId,
                Timestamp.from(asOf));
    }

    @Override
    public List<DomainEvent> loadFrom(String aggregateId, long fromVersion) {
        return jdbc.query("""
                SELECT id, aggregate_id, version, event_type, payload::text, occurred_at
                FROM account_events
                WHERE aggregate_id = ? AND version >= ?
                ORDER BY version ASC
                """,
                this::mapRow,
                aggregateId,
                fromVersion);
    }

    private DomainEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return serializer.deserialize(rs.getString("event_type"), rs.getString("payload"));
    }
}
