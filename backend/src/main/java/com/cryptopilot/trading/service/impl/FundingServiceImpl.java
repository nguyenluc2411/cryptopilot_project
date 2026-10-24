package com.cryptopilot.trading.service.impl;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.trading.calculator.FundingCalculator;
import com.cryptopilot.trading.model.FundingFee;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.service.FundingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads the pair's funding history for the period and leaves the sum and the completeness check to
 * {@link FundingCalculator}. Nothing is cached, so an incomplete period is computed again on the next call and becomes
 * complete as soon as its settlement is stored (A-05). Each settlement still missing is logged as a warning with the
 * pair and its instant, and a gap whose settlements cannot be told is logged once.
 *
 * <p>Rule: BR-37; NSF-08 as realized by A-05; TECHNICAL_DESIGN 7.8.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FundingServiceImpl implements FundingService {

    private static final Logger log = LoggerFactory.getLogger(FundingServiceImpl.class);

    private final MarketApi market;
    private final Clock clock;

    @Override
    public FundingFee fundingFee(
            UUID pairId, Direction direction, BigDecimal quantity, Instant entryTime, Instant exitTime) {
        FundingFee fee = FundingCalculator.fundingFee(
                direction, quantity, entryTime, exitTime, market.fundingHistory(pairId, entryTime, exitTime));
        for (Instant missing : fee.missingFundingTimes()) {
            log.warn(
                    "BR-37 pair {} has no stored funding settlement at {}; the funding of the position open from {}"
                            + " to {} is provisional until it is stored",
                    pairId,
                    missing,
                    entryTime,
                    exitTime);
        }
        if (fee.incomplete() && fee.missingFundingTimes().isEmpty()) {
            log.warn(
                    "BR-37 pair {} has a gap in its mark price snapshots and no known funding interval; the funding of"
                            + " the position open from {} to {} is provisional",
                    pairId,
                    entryTime,
                    exitTime);
        }
        return fee;
    }

    @Override
    public FundingFee fundingSoFar(UUID pairId, Direction direction, BigDecimal quantity, Instant entryTime) {
        Instant now = clock.instant();
        return fundingFee(pairId, direction, quantity, entryTime, now.isBefore(entryTime) ? entryTime : now);
    }
}
