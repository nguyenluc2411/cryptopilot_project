package com.cryptopilot.market.model;

import java.util.List;

/**
 * What one NSF-04 funding settlement check stored.
 *
 * <p>Rule: NSF-04; BR-11.
 *
 * @param pairsChecked pairs looked at
 * @param pairsDue pairs with a settlement not yet stored, whose rates were read
 * @param rowsInserted settled rates stored
 * @param withoutMarkPrice settled rates the exchange sent without a mark price, not stored
 * @param defects the pairs (or {@code fundingInfo}) the exchange rejected or answered unreadably
 */
public record SettlementRun(
        int pairsChecked, int pairsDue, int rowsInserted, int withoutMarkPrice, List<String> defects) {}
