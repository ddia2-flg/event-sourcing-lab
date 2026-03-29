package org.borysovski.eventsourcing.domain.event;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountOpened(
        String aggregateId,
        String owner,
        BigDecimal initialBalance,
        Instant occurredAt
) implements DomainEvent {}
