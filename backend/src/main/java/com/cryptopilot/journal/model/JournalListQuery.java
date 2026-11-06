package com.cryptopilot.journal.model;

import com.cryptopilot.journal.model.enums.PerformanceSource;
import com.cryptopilot.journal.model.enums.TradeResult;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The filters and the page of the journal list (SCR-20). Every filter is optional.
 *
 * <p>Rule: SRS 3.6.1; UC-22; BR-41, BR-42; CR-04.
 *
 * @param status OPEN or CLOSED, or {@code null} for both
 * @param pairId the pair, or {@code null}
 * @param market the market, or {@code null}
 * @param from the first day of entry counted, or {@code null} from the first trade
 * @param to the first day of entry no longer counted, or {@code null} for tomorrow
 * @param source the source, or {@code null} for SIMULATED
 * @param strategyId the strategy, or {@code null}
 * @param result the result, which only a CLOSED trade has, or {@code null}
 * @param page the page, from 1, or {@code null} for 1
 * @param pageSize 1 to 100, or {@code null} for 20
 */
public record JournalListQuery(
        PositionStatus status,
        UUID pairId,
        MarketType market,
        LocalDate from,
        LocalDate to,
        PerformanceSource source,
        UUID strategyId,
        TradeResult result,
        Integer page,
        Integer pageSize) {}
