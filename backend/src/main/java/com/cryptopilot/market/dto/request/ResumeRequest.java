package com.cryptopilot.market.dto.request;

import com.cryptopilot.market.model.enums.MarketType;
import jakarta.validation.constraints.NotNull;

/**
 * An administrator reopens a recovering market by hand (D-159), for an outage longer than can be replayed.
 *
 * @param market the market
 */
public record ResumeRequest(@NotNull(message = "MSG01") MarketType market) {}
