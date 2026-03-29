package org.borysovski.eventsourcing.application.dto.query;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountSummaryDto(
        String accountId,
        String owner,
        BigDecimal balance,
        long version,
        Instant asOf
) {}
