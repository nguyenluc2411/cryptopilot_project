package com.cryptopilot.watchlist.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of SCR-12. Prices, the 24h change and the setup score are not here: the client reads them by symbol from the
 * market endpoints and streams it already uses for SCR-09 (D-75).
 *
 * <p>Rule: SRS 3.4.1; BR-07; D-75.
 *
 * @param id the row, used to edit or remove it
 * @param pairId the pair
 * @param symbol the exchange symbol, e.g. {@code BTCUSDT}
 * @param spotListed whether the pair is enabled on Spot now
 * @param futuresListed whether the pair is enabled on Futures now; a row whose pair is on neither stays in the list
 * @param label the group, or {@code null}
 * @param note the note, or {@code null}
 * @param sortOrder the position, smallest first
 * @param activeAlerts the pair's ACTIVE alerts
 * @param addedAt when the pair was added
 */
public record WatchlistItemResponse(
        UUID id,
        UUID pairId,
        String symbol,
        boolean spotListed,
        boolean futuresListed,
        String label,
        String note,
        int sortOrder,
        long activeAlerts,
        Instant addedAt) {}
