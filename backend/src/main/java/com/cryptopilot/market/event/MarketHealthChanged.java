package com.cryptopilot.market.event;

import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;

/**
 * A market's data health moved from one state to another (D-159), reconnecting included. Inside the {@code market}
 * module only: it pushes the status to the Traders' banner. Other modules hear {@link MarketTradingPaused},
 * {@link MarketRecoveryStarted} and {@link MarketTradingResumed}.
 *
 * @param market the market
 * @param from its state before
 * @param to its state now
 */
public record MarketHealthChanged(MarketType market, MarketHealthState from, MarketHealthState to) {}
