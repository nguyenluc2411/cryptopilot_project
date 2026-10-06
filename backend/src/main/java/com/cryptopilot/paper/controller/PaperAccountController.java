package com.cryptopilot.paper.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.paper.dto.request.TransferRequest;
import com.cryptopilot.paper.dto.response.LedgerEntryResponse;
import com.cryptopilot.paper.dto.response.PaperAccountResponse;
import com.cryptopilot.paper.dto.response.TransferResponse;
import com.cryptopilot.paper.dto.response.WalletResponse;
import com.cryptopilot.paper.model.LedgerQuery;
import com.cryptopilot.paper.model.OpenedAccount;
import com.cryptopilot.paper.model.RecordedTransfer;
import com.cryptopilot.paper.model.enums.LedgerEntryType;
import com.cryptopilot.paper.model.enums.WalletType;
import com.cryptopilot.paper.service.PaperAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The account and wallet endpoints of demo trading (TR-04), Trader only. Paper money only: nothing here reaches an
 * exchange. The caller is always the owner. The client opens the account with {@code POST /account} when the Trader
 * enters the trading screens; every GET only reads, and answers MSG41 until then.
 *
 * <p>Rule: TR-04; Q-T5.
 */
@Tag(name = "Demo trading: account", description = "Paper account, wallets, transfers and history (TR-04). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/paper")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperAccountController {

    private final PaperAccountService paper;

    @Operation(summary = "Open the caller's paper account with the virtual funds, once (Q-T5); idempotent")
    @ApiResponse(responseCode = "201", description = "Opened now, with the funds granted")
    @ApiResponse(responseCode = "200", description = "Already open; nothing changed")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @PostMapping("/account")
    public ResponseEntity<PaperAccountResponse> open(@AuthenticationPrincipal Jwt caller) {
        OpenedAccount opened = paper.open(callerOf(caller));
        return ResponseEntity.status(opened.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(opened.account());
    }

    @Operation(summary = "The caller's paper account")
    @ApiResponse(responseCode = "200", description = "The account")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: not opened yet; POST /api/v1/paper/account opens it")
    @GetMapping("/account")
    public PaperAccountResponse account(@AuthenticationPrincipal Jwt caller) {
        return paper.account(callerOf(caller));
    }

    @Operation(summary = "One wallet with its balances valued in USDT and BTC")
    @ApiResponse(responseCode = "200", description = "The wallet")
    @ApiResponse(responseCode = "400", description = "MSG01: the wallet is not SPOT or FUTURES")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened yet")
    @GetMapping("/wallets/{wallet}")
    public WalletResponse wallet(@AuthenticationPrincipal Jwt caller, @PathVariable WalletType wallet) {
        return paper.wallet(callerOf(caller), wallet);
    }

    @Operation(summary = "Move USDT between the Spot and Futures wallets; once per clientTransferId")
    @ApiResponse(responseCode = "201", description = "The transfer, made now")
    @ApiResponse(responseCode = "200", description = "The transfer made earlier with the same clientTransferId")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01, MSG15: `errors` names each rejected field; PAPER_INSUFFICIENT_BALANCE (MSG01)")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened yet")
    @ApiResponse(responseCode = "409", description = "MSG43: the clientTransferId was used for another transfer")
    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> transfer(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody TransferRequest request) {
        RecordedTransfer recorded = paper.transfer(callerOf(caller), request);
        return ResponseEntity.status(recorded.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(recorded.transfer());
    }

    @Operation(summary = "The caller's transfers, newest first, 20 per page by default")
    @ApiResponse(responseCode = "200", description = "One page of transfers")
    @ApiResponse(responseCode = "400", description = "MSG15: a parameter is invalid")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened yet")
    @GetMapping("/transfers")
    public PageResponse<TransferResponse> transfers(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "Page from 1; default 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "1 to 100; default 20") @RequestParam(required = false) Integer pageSize) {
        return paper.transfers(callerOf(caller), page, pageSize);
    }

    @Operation(summary = "The caller's transaction history: every balance change, newest first")
    @ApiResponse(responseCode = "200", description = "One page of entries")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15: a parameter is invalid")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened yet")
    @GetMapping("/ledger")
    public PageResponse<LedgerEntryResponse> ledger(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "SPOT or FUTURES; both when absent") @RequestParam(required = false)
                    WalletType wallet,
            @Parameter(description = "One kind of change; every kind when absent") @RequestParam(required = false)
                    LedgerEntryType type,
            @Parameter(description = "At or after, ISO 8601 UTC") @RequestParam(required = false) Instant from,
            @Parameter(description = "Before, ISO 8601 UTC") @RequestParam(required = false) Instant to,
            @Parameter(description = "Page from 1; default 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "1 to 100; default 20") @RequestParam(required = false) Integer pageSize) {
        return paper.ledger(callerOf(caller), new LedgerQuery(wallet, type, from, to, page, pageSize));
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}
