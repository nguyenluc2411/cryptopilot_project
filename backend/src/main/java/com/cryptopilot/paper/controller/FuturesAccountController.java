package com.cryptopilot.paper.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.paper.dto.response.FuturesAccountResponse;
import com.cryptopilot.paper.dto.response.FuturesBalanceResponse;
import com.cryptopilot.paper.dto.response.FuturesIncomeResponse;
import com.cryptopilot.paper.dto.response.FuturesPositionHistoryResponse;
import com.cryptopilot.paper.dto.response.FuturesPositionRiskResponse;
import com.cryptopilot.paper.service.FuturesAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The account and position reads of paper Futures USDⓈ-M, named after the exchange's v3 endpoints
 * ({@code /fapi/v3/positionRisk} becomes {@code /api/v1/paper/futures/positionRisk}), with the same field names; the
 * differences are those of {@link FuturesConfigController}. Every figure is at the current mark prices.
 *
 * <p>Rule: TR-18, TR-19; D-127; Q-T10.
 */
@Tag(
        name = "Demo trading: Futures account",
        description = "Positions with their liquidation price and margin, the account's balances (TR-18). Trader with"
                + " the Futures feature.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/paper/futures")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FuturesAccountController {

    private final FuturesAccountService futures;

    @Operation(summary = "The open positions at the mark. Binance: GET /fapi/v3/positionRisk")
    @ApiResponse(responseCode = "200", description = "The open positions; empty when there are none")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @ApiResponse(
            responseCode = "503",
            description = "MARKET_PRICE_UNAVAILABLE: no mark price of a pair with a position")
    @GetMapping("/positionRisk")
    public List<FuturesPositionRiskResponse> positionRisk(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair; every pair when absent") @RequestParam(required = false)
                    String symbol) {
        return futures.positionRisk(callerOf(caller), symbol);
    }

    @Operation(summary = "The account's margin and balances. Binance: GET /fapi/v3/account")
    @ApiResponse(responseCode = "200", description = "The account")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened yet")
    @ApiResponse(
            responseCode = "503",
            description = "MARKET_PRICE_UNAVAILABLE: no mark price of a pair with a position")
    @GetMapping("/account")
    public FuturesAccountResponse account(@AuthenticationPrincipal Jwt caller) {
        return futures.account(callerOf(caller));
    }

    @Operation(summary = "The balance in short. Binance: GET /fapi/v3/balance")
    @ApiResponse(responseCode = "200", description = "The one asset, USDT")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened yet")
    @ApiResponse(
            responseCode = "503",
            description = "MARKET_PRICE_UNAVAILABLE: no mark price of a pair with a position")
    @GetMapping("/balance")
    public List<FuturesBalanceResponse> balance(@AuthenticationPrincipal Jwt caller) {
        return futures.balance(callerOf(caller));
    }

    @Operation(summary = "The Futures wallet's history, oldest first. Binance: GET /fapi/v1/income")
    @ApiResponse(responseCode = "200", description = "The changes; INSURANCE_COVER is CryptoPilot's own type")
    @ApiResponse(
            responseCode = "400",
            description = "MSG15: an unknown incomeType, limit outside 1 … 1000, or startTime after endTime")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @GetMapping("/income")
    public List<FuturesIncomeResponse> income(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair; every pair when absent") @RequestParam(required = false) String symbol,
            @Parameter(
                            description = "TRANSFER, REALIZED_PNL, COMMISSION, FUNDING_FEE, INSURANCE_CLEAR or"
                                    + " INSURANCE_COVER; every type when absent")
                    @RequestParam(required = false)
                    String incomeType,
            @Parameter(description = "Written at or after, ISO-8601") @RequestParam(required = false) Instant startTime,
            @Parameter(description = "Written before, ISO-8601") @RequestParam(required = false) Instant endTime,
            @Parameter(description = "1 to 1000; 100 when absent") @RequestParam(required = false) Integer limit) {
        return futures.income(callerOf(caller), symbol, incomeType, startTime, endTime, limit);
    }

    @Operation(summary = "The closed positions, newest first (the app's Position History)")
    @ApiResponse(responseCode = "200", description = "The closed positions; a one-way reversal is two")
    @ApiResponse(responseCode = "400", description = "MSG15: limit outside 1 … 1000, or startTime after endTime")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @GetMapping("/positionHistory")
    public List<FuturesPositionHistoryResponse> positionHistory(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair; every pair when absent") @RequestParam(required = false) String symbol,
            @Parameter(description = "Closed at or after, ISO-8601") @RequestParam(required = false) Instant startTime,
            @Parameter(description = "Closed before, ISO-8601") @RequestParam(required = false) Instant endTime,
            @Parameter(description = "1 to 1000; 100 when absent") @RequestParam(required = false) Integer limit) {
        return futures.positionHistory(callerOf(caller), symbol, startTime, endTime, limit);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}
