package com.cryptopilot.market.model;

import com.cryptopilot.market.MarketType;
import java.util.List;

/**
 * What one synchronisation of one market did, for the log and for the tests.
 *
 * <p>Rule: NSF-01.
 *
 * @param market the market synchronised
 * @param reconciled how many pairs of the table were compared with the exchange
 * @param created the symbols created from the seed list, INACTIVE
 * @param filterChanges the symbols whose filters the exchange changed
 * @param flagged the symbols that stopped trading or disappeared from this market since the last run
 */
public record SyncReport(
        MarketType market, int reconciled, List<String> created, List<String> filterChanges, List<String> flagged) {}
