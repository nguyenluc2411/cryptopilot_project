package com.cryptopilot.trading.service.impl;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.calculator.SlippageCalculator;
import com.cryptopilot.trading.event.TradingPlanInvalidated;
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.Invalidation;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.repository.MatchingWatermarkRepository;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.trading.service.MatchingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles a fill or an invalidation against a cancel, an expiry or the other outcome with one compare-and-set on the
 * plan's status: the update only matches an ACTIVE plan, so of two racing writers exactly one changes the row and the
 * other sees nothing to change.
 *
 * <p>Rule: NSF-07, BR-32, BR-33, BR-36, BR-68; TECHNICAL_DESIGN 5.5 and 7.7; D-111 (E1, E5); D-112 (R6).
 *
 * <p>Reference: Herlihy, M. &amp; Shavit, N. (2008). <i>The Art of Multiprocessor Programming</i>. Morgan Kaufmann,
 * ch. 5 (compare-and-set: one of several concurrent callers succeeds, the others observe the new state).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MatchingServiceImpl implements MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingServiceImpl.class);

    private final TradingPlanRepository plans;
    private final MatchingWatermarkRepository watermarks;
    private final SystemSettingApi settings;
    private final MarketApi market;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<TrackedEntry> activeEntries() {
        return plans.findActiveWaitingEntries();
    }

    @Override
    @Transactional
    public boolean fill(Fill fill) {
        BigDecimal price = storedPrice(fill);
        boolean filled =
                plans.executeIfActive(fill.planId(), fill.executedAt(), price, fill.liquidity(), clock.instant()) == 1;
        if (filled) {
            log.info(
                    "NSF-07 plan {} filled at {} as {} ({})",
                    fill.planId(),
                    price,
                    fill.liquidity(),
                    fill.executedAt());
        }
        return filled;
    }

    @Override
    @Transactional
    public boolean invalidate(Invalidation invalidation) {
        boolean invalidated = plans.invalidateIfActive(
                        invalidation.planId(), invalidation.invalidatedAt(), invalidation.reason(), clock.instant())
                == 1;
        if (invalidated) {
            events.publishEvent(new TradingPlanInvalidated(
                    invalidation.planId(),
                    invalidation.entry().userId(),
                    invalidation.reason(),
                    invalidation.invalidatedAt()));
            log.info(
                    "BR-68 plan {} invalidated: {} ({})",
                    invalidation.planId(),
                    invalidation.reason(),
                    invalidation.invalidatedAt());
        }
        return invalidated;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> watermark(MarketType market, UUID pairId) {
        return watermarks.lastCandleOpenTime(market.name(), pairId);
    }

    @Override
    @Transactional
    public void advanceWatermark(MarketType market, UUID pairId, Instant openTime) {
        watermarks.advance(market.name(), pairId, openTime, clock.instant());
    }

    /**
     * The price the fill is stored at. A MAKER fill rested at its own price. A TAKER fill (a triggered STOP) is moved
     * against the Trader by {@code SLIPPAGE_BPS}, read for this fill, and rounded to the pair's tick; it is a
     * stop-market order, so nothing caps it. The tick is read only when there is slippage to round.
     *
     * <p>Rule: BR-36; D-111 (E5); D-112 (R6).
     */
    private BigDecimal storedPrice(Fill fill) {
        if (fill.liquidity() == LiquidityRole.MAKER) {
            return fill.price();
        }
        int basisPoints = settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS);
        if (basisPoints == 0) {
            return fill.price();
        }
        TrackedEntry entry = fill.entry();
        BigDecimal tickSize = market.tradablePair(entry.pairId(), entry.market())
                .map(pair -> pair.filters().tickSize())
                .orElseThrow(() -> new IllegalStateException("no tick size for pair " + entry.pairId() + " on "
                        + entry.market() + " to round the slipped fill of plan " + entry.planId()));
        return SlippageCalculator.takerFill(
                fill.price(), SlippageCalculator.buysOnEntry(entry.direction()), basisPoints, tickSize);
    }
}
