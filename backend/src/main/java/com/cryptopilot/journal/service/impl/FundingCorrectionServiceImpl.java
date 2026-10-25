package com.cryptopilot.journal.service.impl;

import com.cryptopilot.journal.entity.TradingJournal;
import com.cryptopilot.journal.repository.TradingJournalRepository;
import com.cryptopilot.journal.service.FundingCorrectionService;
import com.cryptopilot.trading.ClosedTradeFunding;
import com.cryptopilot.trading.TradeFunding;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes the funding of each closed trade still marked incomplete through {@link TradeFunding}, which reads the
 * stored settlements again, and writes the answer when it differs from the stored one. Nothing else of the trade
 * changes: its realized P/L and fees are final, and its net P/L is derived from the stored amounts, so it follows the
 * corrected funding and stops being provisional once the flag is cleared.
 *
 * <p>Rule: BR-37, BR-38; A-05; D-97 note of 2026-10-24; TECHNICAL_DESIGN 7.8.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FundingCorrectionServiceImpl implements FundingCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(FundingCorrectionServiceImpl.class);

    private final TradingJournalRepository records;
    private final TradeFunding funding;
    private final Clock clock;

    @Override
    @Transactional
    public int correctIncompleteFunding(int batchSize) {
        int corrected = 0;
        for (TradingJournal record : records.findIncompleteFunding(Limit.of(batchSize))) {
            ClosedTradeFunding recomputed = funding.fundingOf(
                    record.getPairId(),
                    record.getDirection(),
                    record.getQuantity(),
                    record.getEntryTime(),
                    record.getExitTime());
            if (unchanged(record, recomputed)) {
                continue;
            }
            corrected += records.correctFunding(
                    record.getId(),
                    recomputed.amount(),
                    recomputed.incomplete(),
                    recomputed.incomplete() ? MissingFundingTimes.literal(recomputed.missingFundingTimes()) : null,
                    clock.instant());
            log.info(
                    "BR-37 journal {} funding corrected to {}{}",
                    record.getId(),
                    recomputed.amount(),
                    recomputed.incomplete() ? ", still incomplete" : ", complete");
        }
        return corrected;
    }

    /** Still incomplete with the same sum and the same missing times: nothing to write. */
    private static boolean unchanged(TradingJournal record, ClosedTradeFunding recomputed) {
        List<Instant> stored =
                record.getMissingFundingTimes() == null ? List.of() : Arrays.asList(record.getMissingFundingTimes());
        return recomputed.incomplete()
                && recomputed.amount().compareTo(record.getFundingFeeAmount()) == 0
                && recomputed.missingFundingTimes().equals(stored);
    }
}
