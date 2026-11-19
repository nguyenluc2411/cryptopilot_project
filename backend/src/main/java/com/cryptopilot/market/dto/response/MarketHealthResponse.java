package com.cryptopilot.market.dto.response;

import com.cryptopilot.market.model.enums.DisplaySource;
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
 * @param expectedEndAt when an administrator expects the maintenance to end, if said; {@code null} otherwise
 * @param retryAt while trading is closed and the exchange refuses the server's calls (a ban or an open circuit), when
 *     they may be tried again; {@code null} otherwise
 * @param recovery how far the market has caught up, while it recovers; {@code null} otherwise
 * @param displaySource where the market's displayed prices come from now: the exchange, the display-only fallback while
 *     its data is lost, or none
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
        List<String> pausedPairs,
        Instant expectedEndAt,
        Instant retryAt,
        MarketRecoveryResponse recovery,
        DisplaySource displaySource) {}
