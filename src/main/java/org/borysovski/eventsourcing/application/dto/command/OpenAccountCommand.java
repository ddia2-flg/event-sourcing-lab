package org.borysovski.eventsourcing.application.dto.command;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record OpenAccountCommand(
        @NotBlank String owner,
        @DecimalMin("0.00") BigDecimal initialBalance
) {}
