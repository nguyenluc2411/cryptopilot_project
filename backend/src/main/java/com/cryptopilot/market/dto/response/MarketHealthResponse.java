package com.cryptopilot.market.dto.response;

import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Whether one market may be traded now, as the Traders' banner shows it (D-158).
 *
 * @param market the market
 * @param state its data health
 * @param tradingOpen whether orders, positions and wallets of the market may be changed now
 * @param since when its data was last live, while trading is closed; {@code null} otherwise
 * @param pausedAt when trading closed, while it is closed; {@code null} otherwise
 * @param reason what closed it, while it is closed; {@code null} otherwise
 * @param message the administrator's message of a maintenance, if any
 * @param incidentId the open incident, or the one that just ended; {@code null} otherwise
 * @param resumedAt when trading reopened, for an incident that ended in the last ten minutes; {@code null} otherwise
 * @param pausedPairs the symbols whose exchange data is lost while the market is open (E5), sorted: their order forms
 *     are locked; empty while the market is closed, as all of them are
 */
public record MarketHealthResponse(
        MarketType market,
        MarketHealthState state,
        boolean tradingOpen,
        Instant since,
        Instant pausedAt,
        IncidentReason reason,
        String message,
        UUID incidentId,
        Instant resumedAt,
        List<String> pausedPairs) {}
