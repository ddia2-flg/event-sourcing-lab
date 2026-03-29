package org.borysovski.eventsourcing.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.borysovski.eventsourcing.infrastructure.kafka.dto.TransferRequestedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Reads unpublished rows from the transfer_outbox table and sends them to Kafka.
 * Runs on a fixed delay to ensure at-least-once delivery even if the application
 * crashes between writing the event and sending to Kafka.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(JdbcTemplate jdbc,
                           KafkaTemplate<String, Object> kafkaTemplate,
                           ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 200)
    @Transactional
    public void publishPending() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, transfer_id, aggregate_id, event_type, payload::text
                FROM transfer_outbox
                WHERE published = false
                ORDER BY id
                LIMIT 100
                """);

        for (Map<String, Object> row : rows) {
            try {
                String eventType = (String) row.get("event_type");
                String payload = (String) row.get("payload");
                String transferId = (String) row.get("transfer_id");
                String aggregateId = (String) row.get("aggregate_id");
                Long id = (Long) row.get("id");

                if ("TransferRequested".equals(eventType)) {
                    var message = objectMapper.readValue(payload, TransferRequestedMessage.class);
                    kafkaTemplate.send("transfer.requested", transferId, message);
                    log.debug("Published TransferRequested for transferId={}", transferId);
                } else {
                    log.warn("Unknown outbox event type '{}', skipping row id={}", eventType, id);
                    continue;
                }

                jdbc.update("UPDATE transfer_outbox SET published = true WHERE id = ?", id);
            } catch (Exception e) {
                log.error("Failed to publish outbox row {}", row.get("id"), e);
            }
        }
    }
}
