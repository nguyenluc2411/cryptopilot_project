package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.event.GapDetected;
import com.cryptopilot.market.model.DroppedGap;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.DroppedGapRepository;
import com.cryptopilot.market.service.DroppedGapService;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the gaps NSF-02 dropped in {@code dropped_backfill_gap}.
 *
 * <p>Rule: NSF-02, NSF-03; A-33.
 *
 * <p>Reference: Hohpe, G. &amp; Woolf, B. (2003). <i>Enterprise Integration Patterns</i>. Addison-Wesley ("Dead
 * Letter Channel": a message that cannot be processed is set aside where it can be inspected, not discarded).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class DroppedGapServiceImpl implements DroppedGapService {

    static final int ERROR_LENGTH = 500;

    private final DroppedGapRepository dropped;

    @Override
    @Transactional
    public void recordDropped(GapDetected gap, int failureCount, RuntimeException lastFailure, Instant droppedAt) {
        dropped.save(new DroppedGap(gap, failureCount, summaryOf(lastFailure), droppedAt));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isDropped(GapDetected gap) {
        return dropped.covers(gap);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DroppedGap> droppedGaps(MarketType market) {
        return dropped.findByMarket(market);
    }

    /**
     * The failure's type and the first line of its message, cut to the column. The first line only, because a
     * driver message can go on to quote the statement and its values; the full detail is in the error log.
     */
    static String summaryOf(RuntimeException failure) {
        String message = failure.getMessage();
        String firstLine =
                message == null ? "" : message.lines().findFirst().orElse("").strip();
        String summary = firstLine.isEmpty()
                ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + ": " + firstLine;
        return summary.length() > ERROR_LENGTH ? summary.substring(0, ERROR_LENGTH) : summary;
    }
}
