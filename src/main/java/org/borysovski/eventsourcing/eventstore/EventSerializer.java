package org.borysovski.eventsourcing.eventstore;

import org.borysovski.eventsourcing.domain.event.DomainEvent;

public interface EventSerializer {
    String serialize(DomainEvent event);
    DomainEvent deserialize(String eventType, String payload);
}
