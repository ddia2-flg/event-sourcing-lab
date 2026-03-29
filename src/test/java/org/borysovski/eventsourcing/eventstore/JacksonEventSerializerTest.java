package org.borysovski.eventsourcing.eventstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.borysovski.eventsourcing.domain.event.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

@DisplayName("JacksonEventSerializer")
class JacksonEventSerializerTest {

    private JacksonEventSerializer serializer;

    @BeforeEach
    void setUp() {
        var mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        serializer = new JacksonEventSerializer(mapper);
    }

    static Stream<Arguments> allEventTypes() {
        var t = Instant.parse("2026-01-15T10:00:00Z");
        return Stream.of(
                Arguments.of(new AccountOpened("acc-1", "Alice", new BigDecimal("1000"), t), "AccountOpened"),
                Arguments.of(new MoneyDeposited("acc-1", new BigDecimal("500"), "salary", t), "MoneyDeposited"),
                Arguments.of(new MoneyWithdrawn("acc-1", new BigDecimal("200"), "rent", t), "MoneyWithdrawn"),
                Arguments.of(new TransferRequested("acc-1", "txn-1", "acc-2", new BigDecimal("300"), t), "TransferRequested"),
                Arguments.of(new TransferCompleted("acc-1", "txn-1", t), "TransferCompleted"),
                Arguments.of(new TransferFailed("acc-1", "txn-1", "not found", t), "TransferFailed")
        );
    }

    @ParameterizedTest(name = "{1} round-trips correctly")
    @MethodSource("allEventTypes")
    @DisplayName("serialize then deserialize returns equal event")
    void roundTrip(DomainEvent original, String eventType) {
        String json = serializer.serialize(original);
        DomainEvent restored = serializer.deserialize(eventType, json);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("serialize produces valid JSON with aggregateId field")
    void serializesWithAggregateId() {
        var event = new MoneyDeposited("acc-1", new BigDecimal("100"), "test", Instant.now());
        String json = serializer.serialize(event);

        assertThat(json).contains("\"aggregateId\"").contains("\"acc-1\"");
        assertThat(json).contains("\"amount\"").contains("100");
    }

    @Test
    @DisplayName("eventTypeFor returns simple class name")
    void eventTypeForReturnsSimpleName() {
        var event = new AccountOpened("acc-1", "Alice", BigDecimal.TEN, Instant.now());
        assertThat(JacksonEventSerializer.eventTypeFor(event)).isEqualTo("AccountOpened");
    }

    @Test
    @DisplayName("deserialize throws on unknown event type")
    void throwsOnUnknownEventType() {
        assertThatThrownBy(() -> serializer.deserialize("UnknownEvent", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown event type");
    }

    @Test
    @DisplayName("Instant is serialized as ISO string, not timestamp number")
    void instantSerializedAsIsoString() {
        var event = new AccountOpened("acc-1", "Alice", BigDecimal.TEN,
                Instant.parse("2026-01-15T10:00:00Z"));
        String json = serializer.serialize(event);

        assertThat(json).contains("2026-01-15T10:00:00Z");
        assertThat(json).doesNotContain("1736934000");
    }
}
