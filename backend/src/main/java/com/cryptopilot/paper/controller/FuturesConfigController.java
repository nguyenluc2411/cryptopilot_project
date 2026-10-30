package com.cryptopilot.paper.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.paper.dto.request.FuturesLeverageRequest;
import com.cryptopilot.paper.dto.request.FuturesMarginTypeRequest;
import com.cryptopilot.paper.dto.request.FuturesPositionModeRequest;
import com.cryptopilot.paper.dto.response.FuturesAccountConfigResponse;
import com.cryptopilot.paper.dto.response.FuturesCommissionRateResponse;
import com.cryptopilot.paper.dto.response.FuturesLeverageBracketResponse;
import com.cryptopilot.paper.dto.response.FuturesLeverageResponse;
import com.cryptopilot.paper.dto.response.FuturesPositionModeResponse;
import com.cryptopilot.paper.dto.response.FuturesSymbolConfigResponse;
import com.cryptopilot.paper.service.FuturesConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The settings endpoints of paper Futures USDⓈ-M, named after the exchange's ({@code /fapi/v1/...} becomes
 * {@code /api/v1/paper/futures/...}), with the same parameter and field names. Differences: the caller is the Trader of
 * the session, not an API key; bodies are JSON; a refusal is the project's problem detail, carrying the exchange's
 * code and message as {@code binanceCode} and {@code binanceMsg}. Paper money only: nothing here reaches an exchange.
 *
 * <p>Rule: TR-14; D-127; Q-T10.
 */
@Tag(
        name = "Demo trading: Futures settings",
        description = "Position mode, leverage, margin type and brackets of paper Futures (TR-14). Trader with the"
                + " Futures feature.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/paper/futures")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FuturesConfigController {

    private final FuturesConfigService futures;

    @Operation(summary = "The account settings. Binance: GET /fapi/v1/accountConfig")
    @ApiResponse(responseCode = "200", description = "The settings")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the paper account is not opened yet")
    @GetMapping("/accountConfig")
    public FuturesAccountConfigResponse accountConfig(@AuthenticationPrincipal Jwt caller) {
        return futures.accountConfig(callerOf(caller));
    }

    @Operation(summary = "The commission of a pair, zero until configured. Binance: GET /fapi/v1/commissionRate")
    @ApiResponse(
            responseCode = "200",
            description = "makerCommissionRate and takerCommissionRate, 0 for now (plan 2.3)")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the paper account is not opened or the symbol is unknown")
    @GetMapping("/commissionRate")
    public FuturesCommissionRateResponse commissionRate(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair, e.g. BTCUSDT") @RequestParam String symbol) {
        return futures.commissionRate(callerOf(caller), symbol);
    }

    @Operation(summary = "One-way or hedge mode. Binance: GET /fapi/v1/positionSide/dual")
    @ApiResponse(responseCode = "200", description = "The position mode")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the paper account is not opened yet")
    @GetMapping("/positionSide/dual")
    public FuturesPositionModeResponse positionMode(@AuthenticationPrincipal Jwt caller) {
        return futures.positionMode(callerOf(caller));
    }

    @Operation(summary = "Switch between one-way and hedge mode. Binance: POST /fapi/v1/positionSide/dual")
    @ApiResponse(responseCode = "200", description = "The position mode after the change")
    @ApiResponse(responseCode = "400", description = "MSG01: dualSidePosition is missing")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the paper account is not opened yet")
    @ApiResponse(
            responseCode = "409",
            description = "PAPER_POSITION_MODE (-4059 same mode, -4067 open orders, -4068 position);"
                    + " PAPER_USER_IN_LIQUIDATION (-2023)")
    @PostMapping("/positionSide/dual")
    public FuturesPositionModeResponse changePositionMode(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody FuturesPositionModeRequest request) {
        return futures.changePositionMode(callerOf(caller), request);
    }

    @Operation(
            summary = "Leverage and margin type of one pair, or of every pair the Trader configured."
                    + " Binance: GET /fapi/v1/symbolConfig")
    @ApiResponse(responseCode = "200", description = "The settings; a pair never configured shows 20x CROSSED")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @ApiResponse(responseCode = "409", description = "PAIR_NOT_TRADING (MSG43): the pair has no leverage brackets")
    @GetMapping("/symbolConfig")
    public List<FuturesSymbolConfigResponse> symbolConfig(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair, e.g. BTCUSDT; every configured pair when absent")
                    @RequestParam(required = false)
                    String symbol) {
        return futures.symbolConfigs(callerOf(caller), symbol);
    }

    @Operation(summary = "Change a pair's leverage. Binance: POST /fapi/v1/leverage")
    @ApiResponse(responseCode = "200", description = "The leverage and the largest notional it allows")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01, MSG15: a field is missing; PAPER_LEVERAGE (-4028): outside 1 … the pair's highest")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @ApiResponse(
            responseCode = "409",
            description = "PAPER_MAX_POSITION_AT_LEVERAGE (-2027); PAPER_LEVERAGE_MARGIN (-4161 isolated, -2028 cross);"
                    + " PAPER_USER_IN_LIQUIDATION (-2023); PAIR_NOT_TRADING")
    @ApiResponse(responseCode = "503", description = "MARKET_PRICE_UNAVAILABLE: no mark price for an open position")
    @PostMapping("/leverage")
    public FuturesLeverageResponse leverage(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody FuturesLeverageRequest request) {
        return futures.changeLeverage(callerOf(caller), request);
    }

    @Operation(summary = "Change a pair's margin type. Binance: POST /fapi/v1/marginType")
    @ApiResponse(responseCode = "200", description = "The pair's settings after the change")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15: a field is missing or not ISOLATED / CROSSED")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @ApiResponse(
            responseCode = "409",
            description = "PAPER_MARGIN_TYPE (-4046 same type, -4047 open orders, -4048 position);"
                    + " PAPER_USER_IN_LIQUIDATION (-2023); PAIR_NOT_TRADING")
    @PostMapping("/marginType")
    public FuturesSymbolConfigResponse marginType(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody FuturesMarginTypeRequest request) {
        return futures.changeMarginType(callerOf(caller), request);
    }

    @Operation(summary = "The leverage brackets of a pair. Binance: GET /fapi/v1/leverageBracket")
    @ApiResponse(responseCode = "200", description = "The brackets, smallest notionals first")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @ApiResponse(responseCode = "409", description = "PAIR_NOT_TRADING (MSG43): the pair has no leverage brackets")
    @GetMapping("/leverageBracket")
    public FuturesLeverageBracketResponse leverageBracket(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair, e.g. BTCUSDT") @RequestParam String symbol) {
        return futures.leverageBrackets(callerOf(caller), symbol);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}
