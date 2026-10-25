package com.cryptopilot.trading.dto.request;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The values of SCR-17 shared by creating and editing a plan. Optional values ({@code null}) take their default: the
 * entry price of a MARKET plan is the current last price, the risk % comes from the profile, leverage is 1, a LIMIT
 * or STOP plan expires after 7 days, the analysis timeframe follows the Trader's style preset. The capital is not an
 * input: it is always the profile's default capital (D-121).
 *
 * <p>Rule: SRS 3.5.1; BR-21, BR-30, BR-68, BR-69, BR-70; D-111, D-112, D-121.
 */
public interface PlanInputs {

    MarketType market();

    Direction direction();

    EntryType entryType();

    BigDecimal entryPrice();

    BigDecimal stopLoss();

    BigDecimal takeProfit();

    BigDecimal riskPercent();

    Integer leverage();

    Instant expiresAt();

    String note();

    /** The invalidation price of a LIMIT or STOP plan, or {@code null} (BR-68). */
    BigDecimal invalidationPrice();

    /** The plan's own entry deviation limit in R, or {@code null} for the system's (BR-69). */
    BigDecimal maxEntryDeviationR();

    /** The analysis timeframe code, or {@code null} for the style preset's (BR-70, D-112). */
    String analysisTimeframe();

    /** "Save and Activate" rather than "Save as Draft"; {@code null} is a draft. */
    Boolean activate();

    /** Whether the Trader confirmed MSG51 for the entry deviation of a MARKET plan; {@code null} is not (BR-69). */
    Boolean confirmDeviation();
}
