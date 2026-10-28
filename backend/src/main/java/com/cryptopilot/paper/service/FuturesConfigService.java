package com.cryptopilot.paper.service;

import com.cryptopilot.paper.dto.request.FuturesLeverageRequest;
import com.cryptopilot.paper.dto.request.FuturesMarginTypeRequest;
import com.cryptopilot.paper.dto.response.FuturesAccountConfigResponse;
import com.cryptopilot.paper.dto.response.FuturesLeverageBracketResponse;
import com.cryptopilot.paper.dto.response.FuturesLeverageResponse;
import com.cryptopilot.paper.dto.response.FuturesPositionModeResponse;
import com.cryptopilot.paper.dto.response.FuturesSymbolConfigResponse;
import java.util.List;
import java.util.UUID;

/**
 * The settings of paper Futures, as the exchange's USDⓈ-M account and symbol configuration endpoints: position mode,
 * leverage and margin type per pair, and the pairs' leverage brackets. Every call needs the Futures feature of the
 * Trader's plan and an opened paper account.
 *
 * <p>Rule: TR-14; D-127.
 */
public interface FuturesConfigService {

    /** The account settings. */
    FuturesAccountConfigResponse accountConfig(UUID userId);

    /** One-way or hedge mode. */
    FuturesPositionModeResponse positionMode(UUID userId);

    /** The settings of one pair, or of every pair the Trader configured when {@code symbol} is null. */
    List<FuturesSymbolConfigResponse> symbolConfigs(UUID userId, String symbol);

    /** Changes a pair's leverage. */
    FuturesLeverageResponse changeLeverage(UUID userId, FuturesLeverageRequest request);

    /** Changes a pair's margin type. */
    FuturesSymbolConfigResponse changeMarginType(UUID userId, FuturesMarginTypeRequest request);

    /** The brackets of one pair. */
    FuturesLeverageBracketResponse leverageBrackets(UUID userId, String symbol);
}
