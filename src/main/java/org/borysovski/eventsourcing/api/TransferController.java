package org.borysovski.eventsourcing.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.borysovski.eventsourcing.application.AccountCommandService;
import org.borysovski.eventsourcing.application.dto.command.RequestTransferCommand;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers", description = "Cross-account money transfers")
public class TransferController {

    private final AccountCommandService commandService;

    public TransferController(AccountCommandService commandService) {
        this.commandService = commandService;
    }

    @PostMapping
    @Operation(summary = "Initiate a transfer between two accounts (async saga)")
    @ApiResponse(responseCode = "202", description = "Transfer initiated — processed asynchronously via Kafka")
    @ApiResponse(responseCode = "404", description = "Source account not found")
    @ApiResponse(responseCode = "422", description = "Insufficient funds")
    public ResponseEntity<Map<String, String>> initiateTransfer(@Valid @RequestBody RequestTransferCommand cmd) {
        String transferId = commandService.requestTransfer(cmd);
        return ResponseEntity.accepted().body(Map.of(
                "transferId", transferId,
                "status", "PENDING"
        ));
    }
}
