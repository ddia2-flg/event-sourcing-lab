package org.borysovski.eventsourcing.domain.event;

import java.math.BigDecimal;
import java.time.Instant;

public record TransferRequested(
        String aggregateId,
        String transferId,
        String targetAccountId,
        BigDecimal amount,
        Instant occurredAt
) implements DomainEvent {}
