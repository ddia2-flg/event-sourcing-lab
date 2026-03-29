package org.borysovski.eventsourcing.eventstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.borysovski.eventsourcing.domain.event.*;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class JacksonEventSerializer implements EventSerializer {

    private final ObjectMapper objectMapper;

    private static final Map<String, Class<? extends DomainEvent>> REGISTRY = Map.of(
            "AccountOpened",    AccountOpened.class,
            "MoneyDeposited",   MoneyDeposited.class,
            "MoneyWithdrawn",   MoneyWithdrawn.class,
            "TransferRequested", TransferRequested.class,
            "TransferCompleted", TransferCompleted.class,
            "TransferFailed",   TransferFailed.class
    );

    public JacksonEventSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String serialize(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize event: " + event.getClass().getSimpleName(), e);
        }
    }

    @Override
    public DomainEvent deserialize(String eventType, String payload) {
        var clazz = REGISTRY.get(eventType);
        if (clazz == null) throw new IllegalArgumentException("Unknown event type: " + eventType);
        try {
            return objectMapper.readValue(payload, clazz);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize event of type: " + eventType, e);
        }
    }

    public static String eventTypeFor(DomainEvent event) {
        return event.getClass().getSimpleName();
    }
}
