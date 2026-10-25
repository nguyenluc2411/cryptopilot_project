package com.cryptopilot.trading.service.impl;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.event.TradingPlanExpired;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.trading.service.PlanExpiryService;
import com.cryptopilot.trading.service.ReplayLockService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expires each ACTIVE plan whose expiry has come with a compare-and-set on its status, so a fill, a cancel or an
 * invalidation committed first wins and the plan is left as it is; an expired plan publishes
 * {@link TradingPlanExpired}. A plan of a pair whose restart or hole replay is running is left for a later run (D-79):
 * the replay may still fill or invalidate it from a candle before its expiry, which the Trader would otherwise lose.
 *
 * <p>Rule: NSF-09, BR-32, BR-62; D-79; TECHNICAL_DESIGN 7.7.
 *
 * <p>Reference: Herlihy, M. &amp; Shavit, N. (2008). <i>The Art of Multiprocessor Programming</i>. Morgan Kaufmann,
 * ch. 5 (compare-and-set: one of several concurrent callers succeeds, the others observe the new state).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PlanExpiryServiceImpl implements PlanExpiryService {

    private static final Logger log = LoggerFactory.getLogger(PlanExpiryServiceImpl.class);

    private final TradingPlanRepository plans;
    private final ReplayLockService replays;
    private final MarketApi market;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public int expireDue(int batchSize) {
        // To the microsecond, like the columns, so the stored instant reads back as it was written.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int expired = 0;
        for (TradingPlan plan : plans.findExpiring(now, Limit.of(batchSize))) {
            if (replays.isReplaying(plan.getMarket(), plan.getPairId())) {
                log.info(
                        "NSF-09 plan {} not expired yet: its pair {} {} is replaying (D-79)",
                        plan.getId(),
                        plan.getMarket(),
                        plan.getPairId());
                continue;
            }
            if (plans.expireIfActive(plan.getId(), now) == 0) {
                continue;
            }
            expired++;
            events.publishEvent(new TradingPlanExpired(
                    plan.getId(),
                    plan.getUserId(),
                    plan.getMarket(),
                    plan.getPairId(),
                    plan.getDirection(),
                    market.tradablePair(plan.getPairId(), plan.getMarket())
                            .map(TradablePair::symbol)
                            .orElse(null),
                    now));
            log.info("NSF-09 plan {} expired (expiry {})", plan.getId(), plan.getExpiresAt());
        }
        return expired;
    }
}
