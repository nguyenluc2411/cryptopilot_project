package com.cryptopilot.trading.service.impl;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.PlanOrderPlacer;
import com.cryptopilot.trading.event.TradingPlanInvalidated;
import com.cryptopilot.trading.model.Invalidation;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.repository.MatchingWatermarkRepository;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.trading.service.MatchingService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles an invalidation against a cancel, an expiry or the plan's paper fill with one compare-and-set on the plan's
 * status: the update only matches an ACTIVE plan, so of two racing writers exactly one changes the row and the other
 * sees nothing to change. The plan's entry order waiting in the paper book is cancelled with it (RF-33).
 *
 * <p>Rule: NSF-07, BR-32, BR-68; TECHNICAL_DESIGN 5.5 and 7.7; D-111 (M3); D-117; RF-33.
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
    private final ApplicationEventPublisher events;
    private final MarketApi market;
    private final Clock clock;
    /** The plan's entry order waiting in the paper book, cancelled when the plan is invalidated (RF-33). */
    private final ObjectProvider<PlanOrderPlacer> orderPlacer;

    @Override
    @Transactional(readOnly = true)
    public List<TrackedEntry> activeEntries() {
        return plans.findActiveWaitingEntries();
    }

    @Override
    @Transactional
    public boolean invalidate(Invalidation invalidation) {
        boolean invalidated = plans.invalidateIfActive(
                        invalidation.planId(), invalidation.invalidatedAt(), invalidation.reason(), clock.instant())
                == 1;
        if (invalidated) {
            TrackedEntry entry = invalidation.entry();
            orderPlacer.getObject().cancel(entry.userId(), invalidation.planId());
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
    @Transactional(readOnly = true)
    public Optional<Instant> watermark(MarketType market, UUID pairId) {
        return watermarks.lastCandleOpenTime(market.name(), pairId);
    }

    @Override
    @Transactional
    public void advanceWatermark(MarketType market, UUID pairId, Instant openTime) {
        watermarks.advance(market.name(), pairId, openTime, clock.instant());
    }
}
