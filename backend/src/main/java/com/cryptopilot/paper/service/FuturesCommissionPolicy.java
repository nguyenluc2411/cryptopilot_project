package com.cryptopilot.paper.service;

import com.cryptopilot.paper.model.enums.Liquidity;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * The one place the commission of paper Futures is decided (plan 2.3): every fill and {@code commissionRate} ask it.
 * Commission is a service fee the admin will configure later; until then it is zero, so a fill records a commission of
 * zero and no FEE ledger row. Liquidation clearance fees and funding are not commission and do not come from here.
 *
 * <p>Rule: TR-44; D-127; Q-F2.
 */
public interface FuturesCommissionPolicy {

    /**
     * The share of a fill's value the Trader pays, e.g. {@code 0.0002}; zero for no commission.
     *
     * @param liquidity MAKER for a waiting order filled, TAKER for one that took the book
     */
    BigDecimal rate(UUID userId, UUID pairId, Liquidity liquidity);
}
