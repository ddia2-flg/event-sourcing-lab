package org.borysovski.eventsourcing.domain.event;

import java.time.Instant;

public record TransferFailed(
        String aggregateId,
        String transferId,
        String reason,
        Instant occurredAt
) implements DomainEvent {}
