package com.cryptopilot.trading.model;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;

/**
 * A plan's values before the defaults are applied: as a request carries them, or as a stored plan holds them when it
 * is calculated again at activation. {@code null} means "take the default" for entry price (MARKET), risk % and
 * leverage. The capital is not a term of the plan: every calculation takes the profile's default capital of that
 * moment (D-121).
 *
 * <p>Rule: SRS 3.5.1; BR-31; D-121.
 *
 * @param market SPOT or FUTURES
 * @param direction LONG or SHORT
 * @param entryType LIMIT, MARKET or STOP
 * @param entryPrice the LIMIT entry price or the STOP price; not read for MARKET
 * @param stopLoss the stop loss
 * @param takeProfit the take profit
 * @param riskPercent the risk %, or {@code null}
 * @param leverage the leverage, or {@code null} for 1
 */
public record PlanTerms(
        MarketType market,
        Direction direction,
        EntryType entryType,
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        BigDecimal riskPercent,
        Integer leverage) {}
