package com.cryptopilot.market.dto.response;

import com.cryptopilot.market.LeverageTier;
import java.util.List;
import java.util.UUID;

/**
 * The leverage brackets of a futures pair (SCR-38), smallest notional first.
 *
 * <p>Rule: UC-46; BR-26, BR-27.
 *
 * @param pairId the pair
 * @param symbol e.g. {@code BTCUSDT}
 * @param brackets the stored brackets; empty when none are entered, and then no futures plan can be sized
 */
public record LeverageBracketsResponse(UUID pairId, String symbol, List<LeverageTier> brackets) {}
