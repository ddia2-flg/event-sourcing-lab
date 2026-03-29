package org.borysovski.eventsourcing.infrastructure.kafka.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record TransferResultMessage(
        String transferId,
        String sourceAccountId,
        String targetAccountId,
        BigDecimal amount,
        boolean success,
        String failureReason,
        Instant processedAt
) {}
