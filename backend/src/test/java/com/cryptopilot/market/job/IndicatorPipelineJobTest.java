package com.cryptopilot.market.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cryptopilot.market.event.CandleClosed;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.StoredIndicators;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.AnalysisPushService;
import com.cryptopilot.market.service.IndicatorPipelineService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The listener hands each closed candle to NSF-05, pushes the row NSF-05 stored and nothing when it stored none, and
 * keeps a failure away from the stream that published it.
 *
 * <p>Rule: NSF-05; D-126.
 */
class IndicatorPipelineJobTest {

    private static final Instant OPEN = Instant.parse("2026-09-25T00:00:00Z");

    private final List<Instant> seen = new ArrayList<>();
    private final List<StoredIndicators> pushed = new ArrayList<>();
    private final AnalysisPushService push = (candle, row) -> pushed.add(row);

    @Test
    void NSF05_eachClosedCandle_isHandedToThePipeline() {
        IndicatorPipelineJob job = new IndicatorPipelineJob(
                candle -> {
                    seen.add(candle.openTime());
                    return Optional.empty();
                },
                push);

        job.onCandleClosed(candle(OPEN));
        job.onCandleClosed(candle(OPEN.plusSeconds(3600)));

        assertThat(seen).containsExactly(OPEN, OPEN.plusSeconds(3600));
        assertThat(pushed).isEmpty();
    }

    /** D-126: the row NSF-05 stored for the candle is what is pushed. */
    @Test
    void NSF05_aStoredRow_isPushed() {
        StoredIndicators row = row(OPEN);
        IndicatorPipelineJob job = new IndicatorPipelineJob(candle -> Optional.of(row), push);

        job.onCandleClosed(candle(OPEN));

        assertThat(pushed).containsExactly(row);
    }

    @Test
    void NSF05_aFailingCandle_isLoggedAndDoesNotReachThePublisher() {
        IndicatorPipelineService failing = candle -> {
            throw new IllegalStateException("database unavailable");
        };
        IndicatorPipelineJob job = new IndicatorPipelineJob(failing, push);

        assertThatCode(() -> job.onCandleClosed(candle(OPEN))).doesNotThrowAnyException();
        assertThat(pushed).isEmpty();
    }

    private static StoredIndicators row(Instant open) {
        return new StoredIndicators(
                UUID.randomUUID(),
                open,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ComponentScores(MarketType.SPOT, "1h", "v1", null, null, null, null, null),
                open.plusSeconds(3600));
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
