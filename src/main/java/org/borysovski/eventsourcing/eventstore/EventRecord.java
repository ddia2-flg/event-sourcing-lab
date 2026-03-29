package org.borysovski.eventsourcing.eventstore;

import java.time.Instant;

public record EventRecord(
        Long id,
        String aggregateId,
        long version,
        String eventType,
        String payload,
        Instant occurredAt
) {}
