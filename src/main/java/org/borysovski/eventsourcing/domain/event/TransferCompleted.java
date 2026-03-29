package org.borysovski.eventsourcing.domain.event;

import java.time.Instant;

public record TransferCompleted(
        String aggregateId,
        String transferId,
        Instant occurredAt
) implements DomainEvent {}
