package org.borysovski.eventsourcing.eventstore;

import org.borysovski.eventsourcing.domain.event.DomainEvent;

import java.time.Instant;
import java.util.List;

public interface EventStore {

    /**
     * Append events to an aggregate stream.
     * expectedVersion is the version BEFORE these new events (0 means the aggregate doesn't exist yet).
     * Throws OptimisticLockingException if the stream was concurrently modified.
     */
    void append(String aggregateId, long expectedVersion, List<DomainEvent> events);

    /** Load all events for an aggregate, ordered by version. Returns empty list if not found. */
    List<DomainEvent> load(String aggregateId);

    /** Load events up to (and including) a given timestamp — used for temporal queries. */
    List<DomainEvent> loadUpTo(String aggregateId, Instant asOf);

    /** Load events starting from a given version (inclusive). */
    List<DomainEvent> loadFrom(String aggregateId, long fromVersion);
}
