package org.borysovski.eventsourcing.application;

import org.borysovski.eventsourcing.application.dto.command.*;
import org.borysovski.eventsourcing.domain.event.*;
import org.borysovski.eventsourcing.domain.exception.AccountNotFoundException;
import org.borysovski.eventsourcing.domain.exception.InsufficientFundsException;
import org.borysovski.eventsourcing.eventstore.EventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountCommandService")
class AccountCommandServiceTest {

    @Mock EventStore eventStore;
    @Mock JdbcTemplate jdbc;
    @Mock ApplicationEventPublisher eventPublisher;

    private AccountCommandService service;

    @BeforeEach
    void setUp() {
        var objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        service = new AccountCommandService(eventStore, jdbc, eventPublisher, objectMapper);
    }

    // -------------------------------------------------------
    // openAccount
    // -------------------------------------------------------

    @Test
    @DisplayName("openAccount returns a new UUID and appends AccountOpened event")
    void openAccountAppendsEvent() {
        var cmd = new OpenAccountCommand("Alice", new BigDecimal("1000"));

        String accountId = service.openAccount(cmd);

        assertThat(accountId).isNotBlank();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DomainEvent>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(eventStore).append(eq(accountId), eq(0L), eventsCaptor.capture());

        List<DomainEvent> appended = eventsCaptor.getValue();
        assertThat(appended).hasSize(1);
        assertThat(appended.getFirst()).isInstanceOf(AccountOpened.class);

        var event = (AccountOpened) appended.getFirst();
        assertThat(event.owner()).isEqualTo("Alice");
        assertThat(event.initialBalance()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("openAccount publishes AccountOpened via Spring event bus")
    void openAccountPublishesSpringEvent() {
        service.openAccount(new OpenAccountCommand("Alice", BigDecimal.TEN));
        verify(eventPublisher, atLeastOnce()).publishEvent(any(AccountOpened.class));
    }

    // -------------------------------------------------------
    // deposit
    // -------------------------------------------------------

    @Test
    @DisplayName("deposit appends MoneyDeposited event at correct version")
    void depositAppendsEvent() {
        var t = Instant.now();
        var history = List.<DomainEvent>of(
                new AccountOpened("acc-1", "Alice", new BigDecimal("1000"), t));
        when(eventStore.load("acc-1")).thenReturn(history);

        service.deposit(new DepositMoneyCommand("acc-1", new BigDecimal("500"), "salary"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DomainEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventStore).append(eq("acc-1"), eq(1L), captor.capture());

        assertThat(captor.getValue().getFirst()).isInstanceOf(MoneyDeposited.class);
        var event = (MoneyDeposited) captor.getValue().getFirst();
        assertThat(event.amount()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("deposit throws AccountNotFoundException for missing account")
    void depositThrowsForMissingAccount() {
        when(eventStore.load("missing")).thenReturn(List.of());

        assertThatThrownBy(() -> service.deposit(
                new DepositMoneyCommand("missing", BigDecimal.ONE, "test")))
                .isInstanceOf(AccountNotFoundException.class);
    }

    // -------------------------------------------------------
    // withdraw
    // -------------------------------------------------------

    @Test
    @DisplayName("withdraw appends MoneyWithdrawn event")
    void withdrawAppendsEvent() {
        var t = Instant.now();
        when(eventStore.load("acc-1")).thenReturn(List.of(
                new AccountOpened("acc-1", "Alice", new BigDecimal("1000"), t)));

        service.withdraw(new WithdrawMoneyCommand("acc-1", new BigDecimal("300"), "rent"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DomainEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventStore).append(eq("acc-1"), eq(1L), captor.capture());

        assertThat(captor.getValue().getFirst()).isInstanceOf(MoneyWithdrawn.class);
    }

    @Test
    @DisplayName("withdraw throws InsufficientFundsException when balance too low")
    void withdrawThrowsOnInsufficientFunds() {
        var t = Instant.now();
        when(eventStore.load("acc-1")).thenReturn(List.of(
                new AccountOpened("acc-1", "Alice", new BigDecimal("100"), t)));

        assertThatThrownBy(() -> service.withdraw(
                new WithdrawMoneyCommand("acc-1", new BigDecimal("9999"), "big spend")))
                .isInstanceOf(InsufficientFundsException.class);
    }

    // -------------------------------------------------------
    // completeTransfer / failTransfer
    // -------------------------------------------------------

    @Test
    @DisplayName("completeTransfer appends TransferCompleted event")
    void completeTransferAppendsEvent() {
        var t = Instant.now();
        when(eventStore.load("acc-1")).thenReturn(List.of(
                new AccountOpened("acc-1", "Alice", new BigDecimal("1000"), t),
                new TransferRequested("acc-1", "txn-1", "bob", new BigDecimal("300"), t)));

        service.completeTransfer("acc-1", "txn-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DomainEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventStore).append(eq("acc-1"), eq(2L), captor.capture());
        assertThat(captor.getValue().getFirst()).isInstanceOf(TransferCompleted.class);
    }

    @Test
    @DisplayName("failTransfer appends TransferFailed and compensating MoneyDeposited")
    void failTransferAppendsCompensation() {
        var t = Instant.now();
        when(eventStore.load("acc-1")).thenReturn(List.of(
                new AccountOpened("acc-1", "Alice", new BigDecimal("1000"), t),
                new TransferRequested("acc-1", "txn-1", "bad-bob", new BigDecimal("300"), t)));

        service.failTransfer("acc-1", "txn-1", "target not found", new BigDecimal("300"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DomainEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventStore).append(eq("acc-1"), eq(2L), captor.capture());

        List<DomainEvent> appended = captor.getValue();
        assertThat(appended).hasSize(2);
        assertThat(appended.get(0)).isInstanceOf(TransferFailed.class);
        assertThat(appended.get(1)).isInstanceOf(MoneyDeposited.class);

        // Compensating deposit restores the full amount
        var compensation = (MoneyDeposited) appended.get(1);
        assertThat(compensation.amount()).isEqualByComparingTo("300");
        assertThat(compensation.description()).contains("rollback");
    }
}
