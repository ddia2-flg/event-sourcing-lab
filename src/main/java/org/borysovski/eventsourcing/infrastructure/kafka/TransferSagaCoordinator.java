package org.borysovski.eventsourcing.infrastructure.kafka;

import org.borysovski.eventsourcing.domain.aggregate.AccountAggregate;
import org.borysovski.eventsourcing.domain.event.DomainEvent;
import org.borysovski.eventsourcing.domain.exception.AccountNotFoundException;
import org.borysovski.eventsourcing.eventstore.EventStore;
import org.borysovski.eventsourcing.infrastructure.kafka.dto.TransferRequestedMessage;
import org.borysovski.eventsourcing.infrastructure.kafka.dto.TransferResultMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Listens to transfer.requested, credits the target account, and publishes
 * a success or failure result back to transfer.result.
 */
@Component
public class TransferSagaCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TransferSagaCoordinator.class);

    private final EventStore eventStore;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public TransferSagaCoordinator(EventStore eventStore,
                                   KafkaTemplate<String, Object> kafkaTemplate,
                                   ApplicationEventPublisher eventPublisher) {
        this.eventStore = eventStore;
        this.kafkaTemplate = kafkaTemplate;
        this.eventPublisher = eventPublisher;
    }

    @KafkaListener(topics = "transfer.requested", groupId = "transfer-saga-coordinator")
    @Transactional
    public void onTransferRequested(@Payload TransferRequestedMessage msg) {
        log.info("Processing transfer: transferId={}, from={}, to={}, amount={}",
                msg.transferId(), msg.sourceAccountId(), msg.targetAccountId(), msg.amount());

        try {
            List<DomainEvent> events = eventStore.load(msg.targetAccountId());
            if (events.isEmpty()) {
                throw new AccountNotFoundException(msg.targetAccountId());
            }
            var target = AccountAggregate.reconstitute(events);
            long versionBefore = target.getVersion();
            target.deposit(msg.amount(), "Transfer from " + msg.sourceAccountId());

            eventStore.append(target.getId(), versionBefore, target.getPendingEvents());
            target.getPendingEvents().forEach(eventPublisher::publishEvent);
            target.clearPendingEvents();

            kafkaTemplate.send("transfer.result", msg.transferId(), new TransferResultMessage(
                    msg.transferId(), msg.sourceAccountId(), msg.targetAccountId(),
                    msg.amount(), true, null, Instant.now()));

        } catch (Exception e) {
            log.warn("Transfer failed: transferId={}, reason={}", msg.transferId(), e.getMessage());
            kafkaTemplate.send("transfer.result", msg.transferId(), new TransferResultMessage(
                    msg.transferId(), msg.sourceAccountId(), msg.targetAccountId(),
                    msg.amount(), false, e.getMessage(), Instant.now()));
        }
    }
}
