package org.borysovski.eventsourcing.application.dto.command;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record RequestTransferCommand(
        @NotBlank String sourceAccountId,
        @NotBlank String targetAccountId,
        @DecimalMin("0.01") BigDecimal amount
) {}
