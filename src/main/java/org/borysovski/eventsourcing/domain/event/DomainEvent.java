package org.borysovski.eventsourcing.domain.event;

import java.time.Instant;

public sealed interface DomainEvent
        permits AccountOpened, MoneyDeposited, MoneyWithdrawn,
                TransferRequested, TransferCompleted, TransferFailed {

    String aggregateId();
    Instant occurredAt();
}
