package com.cryptopilot.journal.model;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.util.UUID;

/**
 * The filters and the page of the journal list (SCR-20). Every filter is optional.
 *
 * <p>Rule: SRS 3.6.1; UC-22; CR-04.
 *
 * @param status OPEN or CLOSED, or {@code null} for both
 * @param pairId the pair, or {@code null}
 * @param market the market, or {@code null}
 * @param page the page, from 1, or {@code null} for 1
 * @param pageSize 1 to 100, or {@code null} for 20
 */
public record JournalListQuery(PositionStatus status, UUID pairId, MarketType market, Integer page, Integer pageSize) {}
