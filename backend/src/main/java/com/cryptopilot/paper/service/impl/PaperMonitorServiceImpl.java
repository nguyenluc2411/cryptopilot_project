package com.cryptopilot.paper.service.impl;

import com.cryptopilot.paper.dto.response.AdminPaperOverviewResponse;
import com.cryptopilot.paper.repository.PaperMetrics;
import com.cryptopilot.paper.service.PaperMatchingService;
import com.cryptopilot.paper.service.PaperMonitorService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The demo exchange's overview (TR-30), from the figures {@link PaperMetrics} gives the dashboard, read over the last
 * 24 hours, and the size of the matching engine's book in memory.
 *
 * <p>Rule: TR-30; plan section 9.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperMonitorServiceImpl implements PaperMonitorService {

    /** How many of the latest liquidations the overview lists. */
    static final int LATEST_LIQUIDATIONS = 10;

    private final PaperMetrics metrics;
    private final PaperMatchingService matching;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public AdminPaperOverviewResponse overview() {
        Instant now = clock.instant();
        Map<String, Object> figures = metrics.indicators(now.minus(Duration.ofHours(24)), now, now);
        return new AdminPaperOverviewResponse(
                now,
                (Long) figures.get("paperAccounts"),
                (Long) figures.get("paperAccountsOpened"),
                (Map<String, Long>) figures.get("paperWorkingOrders"),
                (Long) figures.get("paperOpenPositions"),
                (Long) figures.get("paperFills"),
                (Long) figures.get("paperLiquidations"),
                matching.restingOrders().size(),
                metrics.latestLiquidations(LATEST_LIQUIDATIONS).stream()
                        .map(row -> new AdminPaperOverviewResponse.Liquidation(
                                (Instant) row.get("at"),
                                (String) row.get("symbol"),
                                (UUID) row.get("userId"),
                                (String) row.get("marginType"),
                                (BigDecimal) row.get("realizedPnl")))
                        .toList());
    }
}
