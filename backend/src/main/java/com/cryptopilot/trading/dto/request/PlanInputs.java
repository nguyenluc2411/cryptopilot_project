package com.cryptopilot.trading.dto.request;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The values of SCR-17 shared by creating and editing a plan. Optional values ({@code null}) take their default: the
 * entry price of a MARKET plan is the current last price, capital and risk % come from the profile, leverage is 1,
 * a LIMIT plan expires after 7 days.
 *
 * <p>Rule: SRS 3.5.1; BR-21, BR-30.
 */
public interface PlanInputs {

    MarketType market();

    Direction direction();

    EntryType entryType();

    BigDecimal entryPrice();

    BigDecimal stopLoss();

    BigDecimal takeProfit();

    BigDecimal capital();

    BigDecimal riskPercent();

    Integer leverage();

    Instant expiresAt();

    String note();

    /** "Save and Activate" rather than "Save as Draft"; {@code null} is a draft. */
    Boolean activate();
}
