package org.borysovski.eventsourcing.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.borysovski.eventsourcing.application.dto.command.*;
import org.borysovski.eventsourcing.domain.aggregate.AccountAggregate;
import org.borysovski.eventsourcing.domain.event.DomainEvent;
import org.borysovski.eventsourcing.domain.event.TransferRequested;
import org.borysovski.eventsourcing.infrastructure.kafka.dto.TransferRequestedMessage;
import org.borysovski.eventsourcing.domain.exception.AccountNotFoundException;
import org.borysovski.eventsourcing.eventstore.EventStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AccountCommandService {

    private final EventStore eventStore;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public AccountCommandService(EventStore eventStore,
                                 JdbcTemplate jdbc,
                                 ApplicationEventPublisher eventPublisher,
                                 ObjectMapper objectMapper) {
        this.eventStore = eventStore;
        this.jdbc = jdbc;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public String openAccount(OpenAccountCommand cmd) {
        String accountId = UUID.randomUUID().toString();
        var account = AccountAggregate.create(accountId);
        account.open(cmd.owner(), cmd.initialBalance());
        saveAndPublish(account, 0L);
        return accountId;
    }

    @Transactional
    public void deposit(DepositMoneyCommand cmd) {
        var account = load(cmd.accountId());
        long versionBefore = account.getVersion();
        account.deposit(cmd.amount(), cmd.description());
        saveAndPublish(account, versionBefore);
    }

    @Transactional
    public void withdraw(WithdrawMoneyCommand cmd) {
        var account = load(cmd.accountId());
        long versionBefore = account.getVersion();
        account.withdraw(cmd.amount(), cmd.description());
        saveAndPublish(account, versionBefore);
    }

    /**
     * Initiates a transfer by debiting the source account and writing a TransferRequested event
     * plus an outbox row — all within a single transaction.
     * The outbox publisher will later deliver the message to Kafka asynchronously.
     */
    @Transactional
    public String requestTransfer(RequestTransferCommand cmd) {
        String transferId = UUID.randomUUID().toString();
        var account = load(cmd.sourceAccountId());
        long versionBefore = account.getVersion();
        account.requestTransfer(transferId, cmd.targetAccountId(), cmd.amount());

        // Capture the event before saveAndPublish clears pendingEvents
        TransferRequested transferEvent = account.getPendingEvents().stream()
                .filter(e -> e instanceof TransferRequested)
                .map(e -> (TransferRequested) e)
                .findFirst()
                .orElseThrow();

        saveAndPublish(account, versionBefore);

        // Write outbox row in same transaction — guarantees at-least-once delivery to Kafka
        writeOutbox(transferId, cmd.sourceAccountId(), transferEvent);

        return transferId;
    }

    // Called by TransferSagaCoordinator after crediting target
    @Transactional
    public void completeTransfer(String sourceAccountId, String transferId) {
        var account = load(sourceAccountId);
        long versionBefore = account.getVersion();
        account.completeTransfer(transferId);
        saveAndPublish(account, versionBefore);
    }

    // Called when saga fails — appends TransferFailed + compensating MoneyDeposited
    @Transactional
    public void failTransfer(String sourceAccountId, String transferId, String reason, java.math.BigDecimal amount) {
        var account = load(sourceAccountId);
        long versionBefore = account.getVersion();
        account.failTransfer(transferId, reason);
        // Compensating deposit to restore balance
        account.deposit(amount, "Transfer rollback for transferId=" + transferId);
        saveAndPublish(account, versionBefore);
    }

    // -------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------

    private AccountAggregate load(String accountId) {
        List<DomainEvent> events = eventStore.load(accountId);
        if (events.isEmpty()) throw new AccountNotFoundException(accountId);
        return AccountAggregate.reconstitute(events);
    }

    private void saveAndPublish(AccountAggregate account, long expectedVersion) {
        // Defensive copy — clearPendingEvents() would otherwise empty the same list reference
        List<DomainEvent> pending = List.copyOf(account.getPendingEvents());
        eventStore.append(account.getId(), expectedVersion, pending);
        pending.forEach(eventPublisher::publishEvent);
        account.clearPendingEvents();
    }

    private void writeOutbox(String transferId, String aggregateId, TransferRequested event) {
        var message = new TransferRequestedMessage(
                transferId,
                aggregateId,
                event.targetAccountId(),
                event.amount(),
                event.occurredAt());
        jdbc.update("""
                INSERT INTO transfer_outbox (transfer_id, aggregate_id, event_type, payload)
                VALUES (?, ?, ?, ?::jsonb)
                """,
                transferId,
                aggregateId,
                "TransferRequested",
                toJson(message));
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }
}
