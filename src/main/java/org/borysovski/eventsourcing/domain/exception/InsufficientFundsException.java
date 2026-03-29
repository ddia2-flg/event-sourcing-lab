package org.borysovski.eventsourcing.domain.exception;

import java.math.BigDecimal;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(String accountId, BigDecimal balance, BigDecimal requested) {
        super("Account %s has balance %s, requested %s".formatted(accountId, balance, requested));
    }
}
