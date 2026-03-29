package org.borysovski.eventsourcing.projection;

import org.borysovski.eventsourcing.domain.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;

/**
 * Listens to Spring ApplicationEvents published after each successful eventStore.append()
 * and keeps the MongoDB read model in sync.
 * MongoDB is purely a denormalized cache — it can be rebuilt by replaying account_events.
 */
@Component
public class AccountProjectionUpdater {

    private static final Logger log = LoggerFactory.getLogger(AccountProjectionUpdater.class);
    private static final int MAX_RECENT_EVENTS = 20;

    private final AccountProjectionRepository repository;

    public AccountProjectionUpdater(AccountProjectionRepository repository) {
        this.repository = repository;
    }

    @EventListener
    public void on(AccountOpened event) {
        var projection = new AccountProjection(event.aggregateId(), event.owner(), event.initialBalance());
        projection.setVersion(1);
        addRecentEvent(projection, "AccountOpened", event.initialBalance(), "Account opened", event.occurredAt());
        repository.save(projection);
        log.debug("Projection created for account {}", event.aggregateId());
    }

    @EventListener
    public void on(MoneyDeposited event) {
        repository.findById(event.aggregateId()).ifPresent(p -> {
            p.setBalance(p.getBalance().add(event.amount()));
            p.setVersion(p.getVersion() + 1);
            p.setLastUpdated(Instant.now());
            addRecentEvent(p, "MoneyDeposited", event.amount(), event.description(), event.occurredAt());
            repository.save(p);
        });
    }

    @EventListener
    public void on(MoneyWithdrawn event) {
        repository.findById(event.aggregateId()).ifPresent(p -> {
            p.setBalance(p.getBalance().subtract(event.amount()));
            p.setVersion(p.getVersion() + 1);
            p.setLastUpdated(Instant.now());
            addRecentEvent(p, "MoneyWithdrawn", event.amount().negate(), event.description(), event.occurredAt());
            repository.save(p);
        });
    }

    @EventListener
    public void on(TransferRequested event) {
        repository.findById(event.aggregateId()).ifPresent(p -> {
            p.setBalance(p.getBalance().subtract(event.amount()));
            p.setVersion(p.getVersion() + 1);
            p.setLastUpdated(Instant.now());
            addRecentEvent(p, "TransferRequested", event.amount().negate(),
                    "Transfer to " + event.targetAccountId(), event.occurredAt());
            repository.save(p);
        });
    }

    @EventListener
    public void on(TransferCompleted event) {
        repository.findById(event.aggregateId()).ifPresent(p -> {
            p.setVersion(p.getVersion() + 1);
            p.setLastUpdated(Instant.now());
            addRecentEvent(p, "TransferCompleted", null, "Transfer " + event.transferId() + " completed", event.occurredAt());
            repository.save(p);
        });
    }

    @EventListener
    public void on(TransferFailed event) {
        repository.findById(event.aggregateId()).ifPresent(p -> {
            p.setVersion(p.getVersion() + 1);
            p.setLastUpdated(Instant.now());
            addRecentEvent(p, "TransferFailed", null, "Transfer failed: " + event.reason(), event.occurredAt());
            repository.save(p);
        });
    }

    private void addRecentEvent(AccountProjection p, String type,
                                 BigDecimal amount, String description, Instant occurredAt) {
        var events = new ArrayList<>(p.getRecentEvents());
        events.addFirst(new AccountProjection.EventSummary(type, amount, description, occurredAt));
        if (events.size() > MAX_RECENT_EVENTS) {
            events = new ArrayList<>(events.subList(0, MAX_RECENT_EVENTS));
        }
        p.setRecentEvents(events);
    }
}
