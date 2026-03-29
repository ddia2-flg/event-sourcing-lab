package org.borysovski.eventsourcing.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.borysovski.eventsourcing.application.dto.query.AccountSummaryDto;
import org.borysovski.eventsourcing.application.dto.query.EventHistoryDto;
import org.borysovski.eventsourcing.domain.aggregate.AccountAggregate;
import org.borysovski.eventsourcing.domain.event.DomainEvent;
import org.borysovski.eventsourcing.domain.exception.AccountNotFoundException;
import org.borysovski.eventsourcing.eventstore.EventStore;
import org.borysovski.eventsourcing.eventstore.JacksonEventSerializer;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class AccountQueryService {

    private final EventStore eventStore;
    private final ObjectMapper objectMapper;

    public AccountQueryService(EventStore eventStore, ObjectMapper objectMapper) {
        this.eventStore = eventStore;
        this.objectMapper = objectMapper;
    }

    public AccountSummaryDto getBalance(String accountId) {
        var events = loadOrThrow(accountId);
        var account = AccountAggregate.reconstitute(events);
        return new AccountSummaryDto(accountId, account.getOwner(), account.getBalance(),
                account.getVersion(), Instant.now());
    }

    public AccountSummaryDto getBalanceAt(String accountId, Instant asOf) {
        var events = eventStore.loadUpTo(accountId, asOf);
        if (events.isEmpty()) throw new AccountNotFoundException(accountId);
        var account = AccountAggregate.reconstitute(events);
        return new AccountSummaryDto(accountId, account.getOwner(), account.getBalance(),
                account.getVersion(), asOf);
    }

    public List<EventHistoryDto> getHistory(String accountId) {
        var events = loadOrThrow(accountId);
        long[] version = {1};
        return events.stream()
                .map(e -> new EventHistoryDto(
                        version[0]++,
                        JacksonEventSerializer.eventTypeFor(e),
                        e.occurredAt(),
                        toMap(e)))
                .toList();
    }

    private List<DomainEvent> loadOrThrow(String accountId) {
        var events = eventStore.load(accountId);
        if (events.isEmpty()) throw new AccountNotFoundException(accountId);
        return events;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(DomainEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of("error", "could not serialize event");
        }
    }
}
