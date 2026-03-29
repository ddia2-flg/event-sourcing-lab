package org.borysovski.eventsourcing.infrastructure.kafka;

import org.borysovski.eventsourcing.application.AccountCommandService;
import org.borysovski.eventsourcing.infrastructure.kafka.dto.TransferResultMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Listens to transfer.result and finalises the source account:
 * - Success → appends TransferCompleted
 * - Failure → appends TransferFailed + compensating MoneyDeposited (rollback)
 */
@Component
public class TransferResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransferResultConsumer.class);

    private final AccountCommandService commandService;

    public TransferResultConsumer(AccountCommandService commandService) {
        this.commandService = commandService;
    }

    @KafkaListener(topics = "transfer.result", groupId = "transfer-result-consumer")
    public void onTransferResult(@Payload TransferResultMessage msg) {
        if (msg.success()) {
            log.info("Transfer succeeded: transferId={}", msg.transferId());
            commandService.completeTransfer(msg.sourceAccountId(), msg.transferId());
        } else {
            log.warn("Transfer failed: transferId={}, reason={}", msg.transferId(), msg.failureReason());
            commandService.failTransfer(
                    msg.sourceAccountId(),
                    msg.transferId(),
                    msg.failureReason(),
                    msg.amount());
        }
    }
}
