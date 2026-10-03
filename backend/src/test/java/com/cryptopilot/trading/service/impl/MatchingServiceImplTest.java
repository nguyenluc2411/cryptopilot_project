package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.repository.MatchingWatermarkRepository;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatchingServiceImplTest {

    private static final UUID PLAN = UUID.fromString("019b76da-a800-7000-8000-0000000000a1");
    private static final Instant NOW = Instant.parse("2026-10-03T08:00:05Z");
    private static final Instant AT = Instant.parse("2026-10-03T08:00:00Z");

    private final TradingPlanRepository plans = mock(TradingPlanRepository.class);
    private final MatchingWatermarkRepository watermarks = mock(MatchingWatermarkRepository.class);
    private final MatchingServiceImpl service =
            new MatchingServiceImpl(plans, watermarks, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void NSF07_theCompareAndSetThatChangesTheRow_isAFill() {
        when(plans.executeIfActive(PLAN, AT, new BigDecimal("100"), NOW)).thenReturn(1);

        assertThat(service.fill(new Fill(PLAN, new BigDecimal("100"), AT))).isTrue();
    }

    @Test
    void NSF07_theCompareAndSetThatChangesNothing_isNoFill() {
        when(plans.executeIfActive(PLAN, AT, new BigDecimal("100"), NOW)).thenReturn(0);

        assertThat(service.fill(new Fill(PLAN, new BigDecimal("100"), AT))).isFalse();
    }

    @Test
    void A04_theWatermark_isReadAndAdvancedByMarketAndPair() {
        UUID pair = UUID.randomUUID();
        when(watermarks.lastCandleOpenTime("FUTURES", pair)).thenReturn(Optional.of(AT));

        assertThat(service.watermark(MarketType.FUTURES, pair)).contains(AT);
        service.advanceWatermark(MarketType.SPOT, pair, AT);

        verify(watermarks).advance("SPOT", pair, AT, NOW);
    }

    @Test
    void NSF07_theBooksStartFromTheActiveLimitEntries() {
        List<TrackedEntry> entries = List.of(
                new TrackedEntry(PLAN, MarketType.SPOT, UUID.randomUUID(), Direction.LONG, new BigDecimal("100"), AT));
        when(plans.findActiveLimitEntries()).thenReturn(entries);

        assertThat(service.activeEntries()).isEqualTo(entries);
    }
}
