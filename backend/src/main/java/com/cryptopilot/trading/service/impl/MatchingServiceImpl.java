package com.cryptopilot.trading.service.impl;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.PositionLevelChange;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.calculator.SlippageCalculator;
import com.cryptopilot.trading.event.TradingPlanInvalidated;
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.Invalidation;
import com.cryptopilot.trading.model.PositionExit;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.TrackedPosition;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.repository.MatchingWatermarkRepository;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.trading.service.MatchingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * other sees nothing to change. The writer that fills it opens the position in the journal in the same transaction
 * (BR-34); the other writes nothing. An exit is settled the same way on the journal row's status and version.
 *
 * <p>Rule: NSF-07, BR-32, BR-33, BR-34, BR-35, BR-36, BR-68; TECHNICAL_DESIGN 5.5 and 7.7; D-111 (E1, E5); D-112 (R6);
 * D-119.
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
    private final TakerSlippage slippage;
    private final ApplicationEventPublisher events;
    private final PositionOpener positions;
    private final PositionCloser closer;
    private final TradeJournal journal;
    private final MarketApi market;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<TrackedEntry> activeEntries() {
        return plans.findActiveWaitingEntries();
    }

    @Override
    public List<TrackedPosition> openPositions() {
        return journal.openSimulatedTrades().stream().map(TrackedPosition::of).toList();
    }

    @Override
    public Map<UUID, List<PositionLevelChange>> openLevelChanges() {
        return journal.openLevelChanges();
    }

    @Override
    @Transactional
    public Optional<TrackedPosition> fill(Fill fill) {
        BigDecimal price = storedPrice(fill);
        boolean filled =
                plans.executeIfActive(fill.planId(), fill.executedAt(), price, fill.liquidity(), clock.instant()) == 1;
        if (!filled) {
            return Optional.empty();
        }
        log.info("NSF-07 plan {} filled at {} as {} ({})", fill.planId(), price, fill.liquidity(), fill.executedAt());
        // Read again: the compare-and-set cleared the persistence context, and the record takes the price and role
        // it stored (LIMIT MAKER, STOP TAKER), never fill.price(), which is the price before slippage.
        return Optional.of(positions.open(plans.findById(fill.planId()).orElseThrow()));
    }

    @Override
    @Transactional
    public boolean invalidate(Invalidation invalidation) {
        boolean invalidated = plans.invalidateIfActive(
                        invalidation.planId(), invalidation.invalidatedAt(), invalidation.reason(), clock.instant())
                == 1;
        if (invalidated) {
            TrackedEntry entry = invalidation.entry();
            events.publishEvent(new TradingPlanInvalidated(
                    invalidation.planId(),
                    entry.userId(),
                    entry.market(),
                    entry.direction(),
                    market.tradablePair(entry.pairId(), entry.market())
                            .map(TradablePair::symbol)
                            .orElse(null),
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
    @Transactional
    public boolean close(PositionExit exit) {
        return closer.close(exit);
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
     * The price the fill is stored at. A MAKER fill rested at its own price. A TAKER fill (a triggered STOP) is a
     * stop-market order moved against the Trader by {@code SLIPPAGE_BPS}.
     *
     * <p>Rule: BR-36; D-111 (E5); D-112 (R6).
     */
    private BigDecimal storedPrice(Fill fill) {
        if (fill.liquidity() == LiquidityRole.MAKER) {
            return fill.price();
        }
        TrackedEntry entry = fill.entry();
        return slippage.fill(
                fill.price(),
                SlippageCalculator.buysOnEntry(entry.direction()),
                entry.market(),
                entry.pairId(),
                "plan " + entry.planId());
    }
}
