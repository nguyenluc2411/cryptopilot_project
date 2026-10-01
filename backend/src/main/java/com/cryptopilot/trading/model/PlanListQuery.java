package com.cryptopilot.trading.model;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.PlanTab;
import java.time.Instant;
import java.util.UUID;

/**
 * The filters and the page of the plan list (SCR-16). Every filter is optional.
 *
 * <p>Rule: SRS 3.5.2; CR-04.
 *
 * @param tab the status tab, or {@code null} for every status
 * @param pairId the pair, or {@code null}
 * @param market the market, or {@code null}
 * @param from the earliest creation instant, inclusive, or {@code null}
 * @param to the latest creation instant, exclusive, or {@code null}
 * @param page the page, from 1, or {@code null} for 1
 * @param pageSize 1 to 100, or {@code null} for 20
 */
public record PlanListQuery(
        PlanTab tab, UUID pairId, MarketType market, Instant from, Instant to, Integer page, Integer pageSize) {}
