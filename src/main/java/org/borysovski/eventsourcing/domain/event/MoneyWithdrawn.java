package org.borysovski.eventsourcing.domain.event;

import java.math.BigDecimal;
import java.time.Instant;

public record MoneyWithdrawn(
        String aggregateId,
        BigDecimal amount,
        String description,
        Instant occurredAt
) implements DomainEvent {}
