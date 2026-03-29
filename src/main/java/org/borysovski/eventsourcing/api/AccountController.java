package org.borysovski.eventsourcing.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.borysovski.eventsourcing.application.AccountCommandService;
import org.borysovski.eventsourcing.application.AccountQueryService;
import org.borysovski.eventsourcing.application.dto.command.DepositMoneyCommand;
import org.borysovski.eventsourcing.application.dto.command.OpenAccountCommand;
import org.borysovski.eventsourcing.application.dto.command.WithdrawMoneyCommand;
import org.borysovski.eventsourcing.application.dto.query.AccountSummaryDto;
import org.borysovski.eventsourcing.application.dto.query.EventHistoryDto;
import org.borysovski.eventsourcing.projection.AccountProjection;
import org.borysovski.eventsourcing.projection.AccountProjectionRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Bank account operations")
public class AccountController {

    private final AccountCommandService commandService;
    private final AccountQueryService queryService;
    private final AccountProjectionRepository projectionRepository;

    public AccountController(AccountCommandService commandService,
                             AccountQueryService queryService,
                             AccountProjectionRepository projectionRepository) {
        this.commandService = commandService;
        this.queryService = queryService;
        this.projectionRepository = projectionRepository;
    }

    @PostMapping
    @Operation(summary = "Open a new bank account")
    @ApiResponse(responseCode = "201", description = "Account created")
    public ResponseEntity<Map<String, String>> openAccount(@Valid @RequestBody OpenAccountCommand cmd) {
        String accountId = commandService.openAccount(cmd);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}/balance")
                .buildAndExpand(accountId)
                .toUri();
        return ResponseEntity.created(location).body(Map.of("accountId", accountId));
    }

    @PostMapping("/{accountId}/deposits")
    @Operation(summary = "Deposit money into an account")
    @ApiResponse(responseCode = "200", description = "Deposit successful")
    @ApiResponse(responseCode = "404", description = "Account not found")
    public ResponseEntity<Void> deposit(
            @Parameter(description = "Account ID") @PathVariable String accountId,
            @Valid @RequestBody DepositRequest request) {
        commandService.deposit(new DepositMoneyCommand(accountId, request.amount(), request.description()));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{accountId}/withdrawals")
    @Operation(summary = "Withdraw money from an account")
    @ApiResponse(responseCode = "200", description = "Withdrawal successful")
    @ApiResponse(responseCode = "404", description = "Account not found")
    @ApiResponse(responseCode = "422", description = "Insufficient funds")
    public ResponseEntity<Void> withdraw(
            @Parameter(description = "Account ID") @PathVariable String accountId,
            @Valid @RequestBody WithdrawRequest request) {
        commandService.withdraw(new WithdrawMoneyCommand(accountId, request.amount(), request.description()));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{accountId}/balance")
    @Operation(summary = "Get current balance (or balance at a specific time)")
    @ApiResponse(responseCode = "200", description = "Balance retrieved")
    @ApiResponse(responseCode = "404", description = "Account not found")
    public AccountSummaryDto getBalance(
            @Parameter(description = "Account ID") @PathVariable String accountId,
            @Parameter(description = "Point in time (ISO-8601), e.g. 2024-01-01T12:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        if (asOf != null) {
            return queryService.getBalanceAt(accountId, asOf);
        }
        return queryService.getBalance(accountId);
    }

    @GetMapping("/{accountId}/events")
    @Operation(summary = "Get full event history for an account")
    @ApiResponse(responseCode = "200", description = "Event history returned")
    @ApiResponse(responseCode = "404", description = "Account not found")
    public List<EventHistoryDto> getHistory(
            @Parameter(description = "Account ID") @PathVariable String accountId) {
        return queryService.getHistory(accountId);
    }

    @GetMapping
    @Operation(summary = "List all accounts (reads from MongoDB CQRS projection)")
    public List<AccountProjection> listAccounts() {
        return projectionRepository.findAll();
    }

    @GetMapping("/{accountId}/summary")
    @Operation(summary = "Fast account summary from MongoDB (no event replay)")
    @ApiResponse(responseCode = "200", description = "Summary returned")
    @ApiResponse(responseCode = "404", description = "Account not found")
    public ResponseEntity<AccountProjection> getSummary(@PathVariable String accountId) {
        return projectionRepository.findById(accountId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Inline request records (no separate files needed)
    record DepositRequest(
            @jakarta.validation.constraints.DecimalMin("0.01") BigDecimal amount,
            String description) {}

    record WithdrawRequest(
            @jakarta.validation.constraints.DecimalMin("0.01") BigDecimal amount,
            String description) {}
}
