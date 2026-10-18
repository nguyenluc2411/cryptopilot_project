package com.cryptopilot.paper.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.PairCoins;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.UsdtRate;
import com.cryptopilot.paper.model.enums.UsdtRateSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The USDT rate of a fill's quote coin and the pairs paper trading accepts, on a fixed clock. Rule: D-96. */
class QuoteUsdtRateTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:30Z");
    private static final CoinListing USDT = coin("USDT");
    private static final CoinListing BTC = coin("BTC");
    private static final CoinListing ETH = coin("ETH");
    private static final UUID BTCUSDT = UUID.randomUUID();

    private final MarketApi market = mock(MarketApi.class);
    private final QuoteUsdtRate rates = new QuoteUsdtRate(market, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void D96_usdt_isWorthOne() {
        assertThat(rates.of(USDT)).isEqualTo(UsdtRate.ONE);
    }

    @Test
    void D96_aLivePrice_isTheRate() {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("60000")));

        assertThat(rates.of(BTC)).isEqualTo(new UsdtRate(new BigDecimal("60000"), UsdtRateSource.LIVE));
    }

    @Test
    void D96_withoutALivePrice_aClosedMinuteClosedFifteenMinutesAgo_isTheRate() {
        listed(BTCUSDT, true);
        closedMinute(NOW.minus(QuoteUsdtRate.CANDLE_MAX_AGE).minus(Duration.ofMinutes(1)), "59000");

        assertThat(rates.of(BTC)).isEqualTo(new UsdtRate(new BigDecimal("59000"), UsdtRateSource.CANDLE_1M));
    }

    @Test
    void D96_aClosedMinuteOlderThanFifteenMinutes_givesNoRate() {
        listed(BTCUSDT, true);
        closedMinute(
                NOW.minus(QuoteUsdtRate.CANDLE_MAX_AGE)
                        .minus(Duration.ofMinutes(1))
                        .minusMillis(1),
                "59000");

        assertThat(rates.of(BTC)).isEqualTo(UsdtRate.NONE);
    }

    @Test
    void D96_noUsdtPairAtAll_givesNoRate() {
        assertThat(rates.of(BTC)).isEqualTo(UsdtRate.NONE);
    }

    @Test
    void D96_aUsdtPair_isAccepted_withoutLookingUpAnotherPair() {
        rates.requireValued(new PairCoins(UUID.randomUUID(), ETH, USDT));
    }

    @Test
    void D96_aCrossPair_isAccepted_whenBothCoinsHaveAnActiveSpotUsdtPair() {
        when(market.pairListing("ETHUSDT"))
                .thenReturn(Optional.of(new PairListing(UUID.randomUUID(), "ETHUSDT", true, false)));
        listed(BTCUSDT, true);

        rates.requireValued(new PairCoins(UUID.randomUUID(), ETH, BTC));
    }

    @Test
    void D96_aCrossPair_isRefused_whenItsQuoteHasNoActiveSpotUsdtPair() {
        when(market.pairListing("ETHUSDT"))
                .thenReturn(Optional.of(new PairListing(UUID.randomUUID(), "ETHUSDT", true, false)));
        listed(BTCUSDT, false);

        assertThatThrownBy(() -> rates.requireValued(new PairCoins(UUID.randomUUID(), ETH, BTC)))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.PAPER_PAIR_NOT_VALUED));
    }

    private void listed(UUID pairId, boolean spot) {
        when(market.pairListing("BTCUSDT")).thenReturn(Optional.of(new PairListing(pairId, "BTCUSDT", spot, false)));
    }

    private void closedMinute(Instant openTime, String close) {
        BigDecimal price = new BigDecimal(close);
        when(market.latestClosedMinuteKline(MarketType.SPOT, BTCUSDT))
                .thenReturn(Optional.of(new MinuteKline(
                        MarketType.SPOT, BTCUSDT, openTime, price, price, price, true, openTime.plusSeconds(60))));
    }

    private static CoinListing coin(String symbol) {
        return new CoinListing(UUID.randomUUID(), symbol, symbol);
    }
}
