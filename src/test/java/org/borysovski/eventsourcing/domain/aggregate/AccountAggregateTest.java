package org.borysovski.eventsourcing.domain.aggregate;

import org.borysovski.eventsourcing.domain.event.*;
import org.borysovski.eventsourcing.domain.exception.InsufficientFundsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AccountAggregate")
class AccountAggregateTest {

    private static final String ACCOUNT_ID = "acc-001";
    private static final String OWNER = "Alice";
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000.00");

    // -------------------------------------------------------
    // Opening an account
    // -------------------------------------------------------
    @Nested
    @DisplayName("open()")
    class Open {

        @Test
        @DisplayName("emits AccountOpened event with correct state")
        void emitsAccountOpenedEvent() {
            var account = AccountAggregate.create(ACCOUNT_ID);
            account.open(OWNER, INITIAL_BALANCE);

            assertThat(account.getPendingEvents()).hasSize(1);
            assertThat(account.getPendingEvents().getFirst()).isInstanceOf(AccountOpened.class);

            var event = (AccountOpened) account.getPendingEvents().getFirst();
            assertThat(event.aggregateId()).isEqualTo(ACCOUNT_ID);
            assertThat(event.owner()).isEqualTo(OWNER);
            assertThat(event.initialBalance()).isEqualByComparingTo(INITIAL_BALANCE);
        }

        @Test
        @DisplayName("sets balance and marks account as open")
        void setsBalanceAndOpen() {
            var account = AccountAggregate.create(ACCOUNT_ID);
            account.open(OWNER, INITIAL_BALANCE);

            assertThat(account.getBalance()).isEqualByComparingTo(INITIAL_BALANCE);
            assertThat(account.isOpen()).isTrue();
            assertThat(account.getOwner()).isEqualTo(OWNER);
            assertThat(account.getVersion()).isEqualTo(1L);
        }

        @Test
        @DisplayName("throws when opened twice")
        void throwsWhenOpenedTwice() {
            var account = AccountAggregate.create(ACCOUNT_ID);
            account.open(OWNER, INITIAL_BALANCE);

            assertThatThrownBy(() -> account.open(OWNER, INITIAL_BALANCE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already open");
        }

        @Test
        @DisplayName("throws when initial balance is negative")
        void throwsWhenNegativeInitialBalance() {
            var account = AccountAggregate.create(ACCOUNT_ID);

            assertThatThrownBy(() -> account.open(OWNER, new BigDecimal("-1.00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
        }
    }

    // -------------------------------------------------------
    // Reconstitution from history
    // -------------------------------------------------------
    @Nested
    @DisplayName("reconstitute()")
    class Reconstitute {

        @Test
        @DisplayName("replays multiple events and computes correct balance")
        void replaysEventsCorrectly() {
            var t = java.time.Instant.now();
            var events = List.<DomainEvent>of(
                    new AccountOpened(ACCOUNT_ID, OWNER, new BigDecimal("1000"), t),
                    new MoneyDeposited(ACCOUNT_ID, new BigDecimal("500"), "salary", t),
                    new MoneyWithdrawn(ACCOUNT_ID, new BigDecimal("200"), "rent", t)
            );

            var account = AccountAggregate.reconstitute(events);

            assertThat(account.getBalance()).isEqualByComparingTo("1300");
            assertThat(account.getVersion()).isEqualTo(3L);
            assertThat(account.isOpen()).isTrue();
            assertThat(account.getPendingEvents()).isEmpty();
        }

        @Test
        @DisplayName("deducts amount on TransferRequested during replay")
        void deductsOnTransferRequested() {
            var t = java.time.Instant.now();
            var events = List.<DomainEvent>of(
                    new AccountOpened(ACCOUNT_ID, OWNER, new BigDecimal("1000"), t),
                    new TransferRequested(ACCOUNT_ID, "txn-1", "bob", new BigDecimal("300"), t)
            );

            var account = AccountAggregate.reconstitute(events);

            assertThat(account.getBalance()).isEqualByComparingTo("700");
        }

        @Test
        @DisplayName("TransferCompleted and TransferFailed do not change balance")
        void transferCompletedDoesNotChangeBalance() {
            var t = java.time.Instant.now();
            var events = List.<DomainEvent>of(
                    new AccountOpened(ACCOUNT_ID, OWNER, new BigDecimal("1000"), t),
                    new TransferRequested(ACCOUNT_ID, "txn-1", "bob", new BigDecimal("300"), t),
                    new TransferCompleted(ACCOUNT_ID, "txn-1", t)
            );

            var account = AccountAggregate.reconstitute(events);

            // balance stays at 700 — debit was on TransferRequested, Completed just confirms
            assertThat(account.getBalance()).isEqualByComparingTo("700");
        }
    }

    // -------------------------------------------------------
    // Deposit
    // -------------------------------------------------------
    @Nested
    @DisplayName("deposit()")
    class Deposit {

        private AccountAggregate account;

        @BeforeEach
        void setUp() {
            account = AccountAggregate.create(ACCOUNT_ID);
            account.open(OWNER, INITIAL_BALANCE);
            account.clearPendingEvents();
        }

        @Test
        @DisplayName("adds amount to balance and emits MoneyDeposited")
        void addsToBalance() {
            account.deposit(new BigDecimal("300"), "bonus");

            assertThat(account.getBalance()).isEqualByComparingTo("1300");
            assertThat(account.getPendingEvents()).hasSize(1);
            assertThat(account.getPendingEvents().getFirst()).isInstanceOf(MoneyDeposited.class);
        }

        @Test
        @DisplayName("throws when amount is zero")
        void throwsOnZeroAmount() {
            assertThatThrownBy(() -> account.deposit(BigDecimal.ZERO, "zero"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("throws when amount is negative")
        void throwsOnNegativeAmount() {
            assertThatThrownBy(() -> account.deposit(new BigDecimal("-1"), "negative"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // -------------------------------------------------------
    // Withdrawal
    // -------------------------------------------------------
    @Nested
    @DisplayName("withdraw()")
    class Withdraw {

        private AccountAggregate account;

        @BeforeEach
        void setUp() {
            account = AccountAggregate.create(ACCOUNT_ID);
            account.open(OWNER, INITIAL_BALANCE);
            account.clearPendingEvents();
        }

        @Test
        @DisplayName("subtracts amount from balance and emits MoneyWithdrawn")
        void subtractsFromBalance() {
            account.withdraw(new BigDecimal("400"), "rent");

            assertThat(account.getBalance()).isEqualByComparingTo("600");
            assertThat(account.getPendingEvents()).hasSize(1);
            assertThat(account.getPendingEvents().getFirst()).isInstanceOf(MoneyWithdrawn.class);
        }

        @Test
        @DisplayName("throws InsufficientFundsException when amount exceeds balance")
        void throwsOnInsufficientFunds() {
            assertThatThrownBy(() -> account.withdraw(new BigDecimal("9999"), "overdraw"))
                    .isInstanceOf(InsufficientFundsException.class)
                    .hasMessageContaining("1000");
        }

        @Test
        @DisplayName("allows withdrawing exact balance")
        void allowsWithdrawingExactBalance() {
            account.withdraw(INITIAL_BALANCE, "close out");

            assertThat(account.getBalance()).isEqualByComparingTo("0");
        }
    }

    // -------------------------------------------------------
    // Transfer lifecycle
    // -------------------------------------------------------
    @Nested
    @DisplayName("transfer lifecycle")
    class Transfer {

        private AccountAggregate account;

        @BeforeEach
        void setUp() {
            account = AccountAggregate.create(ACCOUNT_ID);
            account.open(OWNER, INITIAL_BALANCE);
            account.clearPendingEvents();
        }

        @Test
        @DisplayName("requestTransfer debits balance immediately")
        void debitsBalanceOnRequest() {
            account.requestTransfer("txn-1", "bob", new BigDecimal("300"));

            assertThat(account.getBalance()).isEqualByComparingTo("700");
            assertThat(account.getPendingEvents().getFirst()).isInstanceOf(TransferRequested.class);
        }

        @Test
        @DisplayName("requestTransfer throws on insufficient funds")
        void throwsOnInsufficientFundsForTransfer() {
            assertThatThrownBy(() -> account.requestTransfer("txn-1", "bob", new BigDecimal("9999")))
                    .isInstanceOf(InsufficientFundsException.class);
        }

        @Test
        @DisplayName("completeTransfer emits TransferCompleted without changing balance")
        void completeTransferEmitsEvent() {
            account.requestTransfer("txn-1", "bob", new BigDecimal("300"));
            account.clearPendingEvents();
            long balanceBefore = account.getBalance().longValue();

            account.completeTransfer("txn-1");

            assertThat(account.getBalance().longValue()).isEqualTo(balanceBefore);
            assertThat(account.getPendingEvents().getFirst()).isInstanceOf(TransferCompleted.class);
        }

        @Test
        @DisplayName("failTransfer emits TransferFailed")
        void failTransferEmitsEvent() {
            account.requestTransfer("txn-1", "bob", new BigDecimal("300"));
            account.clearPendingEvents();

            account.failTransfer("txn-1", "target not found");

            assertThat(account.getPendingEvents().getFirst()).isInstanceOf(TransferFailed.class);
            var event = (TransferFailed) account.getPendingEvents().getFirst();
            assertThat(event.reason()).isEqualTo("target not found");
        }
    }
}
