package org.borysovski.eventsourcing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.borysovski.eventsourcing.application.AccountCommandService;
import org.borysovski.eventsourcing.application.AccountQueryService;
import org.borysovski.eventsourcing.application.dto.command.OpenAccountCommand;
import org.borysovski.eventsourcing.application.dto.query.AccountSummaryDto;
import org.borysovski.eventsourcing.application.dto.query.EventHistoryDto;
import org.borysovski.eventsourcing.domain.exception.AccountNotFoundException;
import org.borysovski.eventsourcing.domain.exception.InsufficientFundsException;
import org.borysovski.eventsourcing.projection.AccountProjection;
import org.borysovski.eventsourcing.projection.AccountProjectionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AccountController.class, TransferController.class, GlobalExceptionHandler.class})
@DisplayName("AccountController")
class AccountControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean AccountCommandService commandService;
    @MockBean AccountQueryService queryService;
    @MockBean AccountProjectionRepository projectionRepository;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // -------------------------------------------------------
    // POST /api/v1/accounts
    // -------------------------------------------------------
    @Nested
    @DisplayName("POST /api/v1/accounts")
    class OpenAccount {

        @Test
        @DisplayName("returns 201 Created with accountId")
        void returns201() throws Exception {
            when(commandService.openAccount(any())).thenReturn("acc-123");

            mockMvc.perform(post("/api/v1/accounts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"owner":"Alice","initialBalance":1000}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accountId").value("acc-123"));
        }

        @Test
        @DisplayName("returns 400 when owner is blank")
        void returns400OnBlankOwner() throws Exception {
            mockMvc.perform(post("/api/v1/accounts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"owner":"","initialBalance":1000}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 400 when initialBalance is negative")
        void returns400OnNegativeBalance() throws Exception {
            mockMvc.perform(post("/api/v1/accounts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"owner":"Alice","initialBalance":-1}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    // -------------------------------------------------------
    // POST /api/v1/accounts/{id}/deposits
    // -------------------------------------------------------
    @Nested
    @DisplayName("POST /api/v1/accounts/{id}/deposits")
    class Deposit {

        @Test
        @DisplayName("returns 200 OK on valid deposit")
        void returns200() throws Exception {
            doNothing().when(commandService).deposit(any());

            mockMvc.perform(post("/api/v1/accounts/acc-1/deposits")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount":500,"description":"salary"}
                                    """))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 404 when account not found")
        void returns404() throws Exception {
            doThrow(new AccountNotFoundException("acc-missing"))
                    .when(commandService).deposit(any());

            mockMvc.perform(post("/api/v1/accounts/acc-missing/deposits")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount":100,"description":"test"}
                                    """))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Account Not Found"));
        }
    }

    // -------------------------------------------------------
    // POST /api/v1/accounts/{id}/withdrawals
    // -------------------------------------------------------
    @Nested
    @DisplayName("POST /api/v1/accounts/{id}/withdrawals")
    class Withdraw {

        @Test
        @DisplayName("returns 200 OK on valid withdrawal")
        void returns200() throws Exception {
            mockMvc.perform(post("/api/v1/accounts/acc-1/withdrawals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount":200,"description":"rent"}
                                    """))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 422 on insufficient funds")
        void returns422OnInsufficientFunds() throws Exception {
            doThrow(new InsufficientFundsException("acc-1", new BigDecimal("100"), new BigDecimal("9999")))
                    .when(commandService).withdraw(any());

            mockMvc.perform(post("/api/v1/accounts/acc-1/withdrawals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount":9999,"description":"big spend"}
                                    """))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.title").value("Insufficient Funds"));
        }
    }

    // -------------------------------------------------------
    // GET /api/v1/accounts/{id}/balance
    // -------------------------------------------------------
    @Nested
    @DisplayName("GET /api/v1/accounts/{id}/balance")
    class GetBalance {

        @Test
        @DisplayName("returns 200 with balance data")
        void returns200WithBalance() throws Exception {
            var dto = new AccountSummaryDto("acc-1", "Alice", new BigDecimal("1300"), 3L, Instant.now());
            when(queryService.getBalance("acc-1")).thenReturn(dto);

            mockMvc.perform(get("/api/v1/accounts/acc-1/balance"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountId").value("acc-1"))
                    .andExpect(jsonPath("$.owner").value("Alice"))
                    .andExpect(jsonPath("$.balance").value(1300))
                    .andExpect(jsonPath("$.version").value(3));
        }

        @Test
        @DisplayName("returns 404 for unknown account")
        void returns404() throws Exception {
            when(queryService.getBalance("unknown"))
                    .thenThrow(new AccountNotFoundException("unknown"));

            mockMvc.perform(get("/api/v1/accounts/unknown/balance"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("delegates to getBalanceAt when asOf param is provided")
        void usesTemporalQueryWhenAsOfProvided() throws Exception {
            var asOf = Instant.parse("2026-01-01T12:00:00Z");
            var dto = new AccountSummaryDto("acc-1", "Alice", new BigDecimal("500"), 1L, asOf);
            when(queryService.getBalanceAt(eq("acc-1"), any(Instant.class))).thenReturn(dto);

            mockMvc.perform(get("/api/v1/accounts/acc-1/balance")
                            .param("asOf", "2026-01-01T12:00:00Z"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(500));

            verify(queryService).getBalanceAt(eq("acc-1"), any(Instant.class));
            verify(queryService, never()).getBalance(any());
        }
    }

    // -------------------------------------------------------
    // GET /api/v1/accounts/{id}/events
    // -------------------------------------------------------
    @Nested
    @DisplayName("GET /api/v1/accounts/{id}/events")
    class GetEvents {

        @Test
        @DisplayName("returns event history list")
        void returnsHistory() throws Exception {
            var events = List.of(
                    new EventHistoryDto(1L, "AccountOpened", Instant.now(),
                            Map.of("owner", "Alice", "initialBalance", 1000)),
                    new EventHistoryDto(2L, "MoneyDeposited", Instant.now(),
                            Map.of("amount", 500, "description", "salary"))
            );
            when(queryService.getHistory("acc-1")).thenReturn(events);

            mockMvc.perform(get("/api/v1/accounts/acc-1/events"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].eventType").value("AccountOpened"))
                    .andExpect(jsonPath("$[1].eventType").value("MoneyDeposited"));
        }
    }

    // -------------------------------------------------------
    // GET /api/v1/accounts (MongoDB list)
    // -------------------------------------------------------
    @Nested
    @DisplayName("GET /api/v1/accounts")
    class ListAccounts {

        @Test
        @DisplayName("returns list from MongoDB projection")
        void returnsProjectionList() throws Exception {
            var p1 = new AccountProjection("acc-1", "Alice", new BigDecimal("1300"));
            var p2 = new AccountProjection("acc-2", "Bob", new BigDecimal("500"));
            when(projectionRepository.findAll()).thenReturn(List.of(p1, p2));

            mockMvc.perform(get("/api/v1/accounts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].owner").value("Alice"))
                    .andExpect(jsonPath("$[1].owner").value("Bob"));
        }
    }

    // -------------------------------------------------------
    // GET /api/v1/accounts/{id}/summary (MongoDB)
    // -------------------------------------------------------
    @Nested
    @DisplayName("GET /api/v1/accounts/{id}/summary")
    class GetSummary {

        @Test
        @DisplayName("returns 200 with projection data")
        void returns200() throws Exception {
            var projection = new AccountProjection("acc-1", "Alice", new BigDecimal("1300"));
            when(projectionRepository.findById("acc-1")).thenReturn(Optional.of(projection));

            mockMvc.perform(get("/api/v1/accounts/acc-1/summary"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.owner").value("Alice"));
        }

        @Test
        @DisplayName("returns 404 when projection not found")
        void returns404() throws Exception {
            when(projectionRepository.findById("missing")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/accounts/missing/summary"))
                    .andExpect(status().isNotFound());
        }
    }
}
