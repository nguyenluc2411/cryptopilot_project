package com.cryptopilot.trading.service.impl;

import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.trading.service.MatchingService;
import java.time.Clock;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles a fill against a cancel or an expiry with one compare-and-set on the plan's status: the update only
 * matches an ACTIVE plan, so of two racing writers exactly one changes the row and the other sees nothing to change.
 *
 * <p>Rule: NSF-07, BR-32, BR-33; TECHNICAL_DESIGN 5.5 and 7.7.
 *
 * <p>Reference: Herlihy, M. &amp; Shavit, N. (2008). <i>The Art of Multiprocessor Programming</i>. Morgan Kaufmann,
 * ch. 5 (compare-and-set: one of several concurrent callers succeeds, the others observe the new state).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MatchingServiceImpl implements MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingServiceImpl.class);

    private final TradingPlanRepository plans;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<TrackedEntry> activeEntries() {
        return plans.findActiveLimitEntries();
    }

    @Override
    @Transactional
    public boolean fill(Fill fill) {
        boolean filled = plans.executeIfActive(fill.planId(), fill.executedAt(), fill.price(), clock.instant()) == 1;
        if (filled) {
            log.info("NSF-07 plan {} filled at {} ({})", fill.planId(), fill.price(), fill.executedAt());
        }
        return filled;
    }
}
