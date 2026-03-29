package org.borysovski.eventsourcing.application.dto.command;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record WithdrawMoneyCommand(
        @NotBlank String accountId,
        @DecimalMin("0.01") BigDecimal amount,
        String description
) {}
