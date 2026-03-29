package org.borysovski.eventsourcing.infrastructure.kafka.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record TransferRequestedMessage(
        String transferId,
        String sourceAccountId,
        String targetAccountId,
        BigDecimal amount,
        Instant requestedAt
) {}
