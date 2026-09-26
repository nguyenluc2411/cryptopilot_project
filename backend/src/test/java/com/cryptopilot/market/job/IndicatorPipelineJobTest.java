package com.cryptopilot.market.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.event.CandleClosed;
import com.cryptopilot.market.service.IndicatorPipelineService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The listener hands each closed candle to NSF-05 and keeps a failure away from the stream that published it.
 *
 * <p>Rule: NSF-05.
 */
class IndicatorPipelineJobTest {

    private static final Instant OPEN = Instant.parse("2026-09-25T00:00:00Z");

    private final List<Instant> seen = new ArrayList<>();

    @Test
    void NSF05_eachClosedCandle_isHandedToThePipeline() {
        IndicatorPipelineJob job = new IndicatorPipelineJob(candle -> {
            seen.add(candle.openTime());
            return Optional.empty();
        });

        job.onCandleClosed(candle(OPEN));
        job.onCandleClosed(candle(OPEN.plusSeconds(3600)));

        assertThat(seen).containsExactly(OPEN, OPEN.plusSeconds(3600));
    }

    @Test
    void NSF05_aFailingCandle_isLoggedAndDoesNotReachThePublisher() {
        IndicatorPipelineService failing = candle -> {
            throw new IllegalStateException("database unavailable");
        };
        IndicatorPipelineJob job = new IndicatorPipelineJob(failing);

        assertThatCode(() -> job.onCandleClosed(candle(OPEN))).doesNotThrowAnyException();
    }

    private static CandleClosed candle(Instant open) {
        BigDecimal price = new BigDecimal("100");
        return new CandleClosed(
                UUID.randomUUID(),
                "BTCUSDT",
                MarketType.SPOT,
                "1h",
                open,
                open.plusSeconds(3599),
                price,
                price,
                price,
                price,
                BigDecimal.ONE,
                price);
    }
}
