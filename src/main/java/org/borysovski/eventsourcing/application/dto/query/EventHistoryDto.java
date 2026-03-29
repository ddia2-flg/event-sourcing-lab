package org.borysovski.eventsourcing.application.dto.query;

import java.time.Instant;
import java.util.Map;

public record EventHistoryDto(
        long version,
        String eventType,
        Instant occurredAt,
        Map<String, Object> payload
) {}
