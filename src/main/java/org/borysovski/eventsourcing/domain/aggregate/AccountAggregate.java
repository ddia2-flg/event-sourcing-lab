package org.borysovski.eventsourcing.domain.aggregate;

import org.borysovski.eventsourcing.domain.event.*;
import org.borysovski.eventsourcing.domain.exception.InsufficientFundsException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AccountAggregate {

    private String id;
    private String owner;
    private BigDecimal balance;
    private long version;
    private boolean open;

    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    private AccountAggregate() {}

    // -------------------------------------------------------
    // Factory: new account (no history yet)
    // -------------------------------------------------------
    public static AccountAggregate create(String id) {
        var account = new AccountAggregate();
        account.id = id;
        return account;
    }

    // -------------------------------------------------------
    // Factory: reconstitute from event history
    // -------------------------------------------------------
    public static AccountAggregate reconstitute(List<DomainEvent> history) {
        var account = new AccountAggregate();
        for (DomainEvent event : history) {
            account.applyEvent(event);
            account.version++;
        }
        return account;
    }

    // -------------------------------------------------------
    // Commands — validate, emit event, apply it
    // -------------------------------------------------------

    public void open(String owner, BigDecimal initialBalance) {
        if (open) throw new IllegalStateException("Account is already open");
        if (initialBalance.compareTo(BigDecimal.ZERO) < 0)
            throw new IllegalArgumentException("Initial balance cannot be negative");
        var event = new AccountOpened(id, owner, initialBalance, Instant.now());
        applyAndRecord(event);
    }

    public void deposit(BigDecimal amount, String description) {
        if (!open) throw new IllegalStateException("Account is closed");
        if (amount.compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Deposit amount must be positive");
        var event = new MoneyDeposited(id, amount, description, Instant.now());
        applyAndRecord(event);
    }

    public void withdraw(BigDecimal amount, String description) {
        if (!open) throw new IllegalStateException("Account is closed");
        if (amount.compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Withdrawal amount must be positive");
        if (amount.compareTo(balance) > 0)
            throw new InsufficientFundsException(id, balance, amount);
        var event = new MoneyWithdrawn(id, amount, description, Instant.now());
        applyAndRecord(event);
    }

    public void requestTransfer(String transferId, String targetAccountId, BigDecimal amount) {
        if (!open) throw new IllegalStateException("Account is closed");
        if (amount.compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Transfer amount must be positive");
        if (amount.compareTo(balance) > 0)
            throw new InsufficientFundsException(id, balance, amount);
        var event = new TransferRequested(id, transferId, targetAccountId, amount, Instant.now());
        applyAndRecord(event);
    }

    public void completeTransfer(String transferId) {
        var event = new TransferCompleted(id, transferId, Instant.now());
        applyAndRecord(event);
    }

    public void failTransfer(String transferId, String reason) {
        var event = new TransferFailed(id, transferId, reason, Instant.now());
        applyAndRecord(event);
    }

    // -------------------------------------------------------
    // Apply — pure state transitions, no validation, no I/O
    // Used during both command handling and reconstitution
    // -------------------------------------------------------
    private void applyEvent(DomainEvent event) {
        switch (event) {
            case AccountOpened e -> {
                this.id = e.aggregateId();
                this.owner = e.owner();
                this.balance = e.initialBalance();
                this.open = true;
            }
            case MoneyDeposited e -> this.balance = this.balance.add(e.amount());
            case MoneyWithdrawn e -> this.balance = this.balance.subtract(e.amount());
            case TransferRequested e -> this.balance = this.balance.subtract(e.amount()); // debit on request
            case TransferCompleted ignored -> {} // balance already debited at TransferRequested
            case TransferFailed ignored -> {}    // compensating deposit issued via separate MoneyDeposited event
        }
    }

    private void applyAndRecord(DomainEvent event) {
        applyEvent(event);
        version++;
        pendingEvents.add(event);
    }

    // -------------------------------------------------------
    // Accessors
    // -------------------------------------------------------
    public String getId() { return id; }
    public String getOwner() { return owner; }
    public BigDecimal getBalance() { return balance; }
    public long getVersion() { return version; }
    public boolean isOpen() { return open; }

    public List<DomainEvent> getPendingEvents() {
        return Collections.unmodifiableList(pendingEvents);
    }

    public void clearPendingEvents() {
        pendingEvents.clear();
    }
}
