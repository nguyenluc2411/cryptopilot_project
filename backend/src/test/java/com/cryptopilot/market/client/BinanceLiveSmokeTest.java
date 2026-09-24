package com.cryptopilot.market.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two cheap calls against the real exchange, to confirm that the documented shapes the parser expects
 * are still the shapes Binance sends.
 *
 * <p>Tagged {@code live} and excluded from the default build (see the Surefire configuration): a CI
 * runner may be geo-blocked with HTTP 451, and a live market is not deterministic. Run it on purpose from
 * a machine that can reach the exchange:
 *
 * <pre>./mvnw test -Dtest=BinanceLiveSmokeTest -Dgroups=live -Dsurefire.excludedGroups=none</pre>
 *
 * <p>It spends a few units of request weight (Spot {@code klines} 2, futures {@code premiumIndex} 1).
 *
 * <p>Rule: BR-09; TECHNICAL_DESIGN 7.1.1.
 */
@Tag("live")
class BinanceLiveSmokeTest {

    private final BinanceRestClient client = new BinanceRestClient(
            new BinanceClientProperties(
                    new BinanceClientProperties.Venue(URI.create("https://api.binance.com"), 6000),
                    new BinanceClientProperties.Venue(URI.create("https://fapi.binance.com"), 2400),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(10),
                    80,
                    Duration.ofMinutes(2),
                    new BinanceClientProperties.Retry(
                            1, Duration.ofMillis(500), 2.0, Duration.ofSeconds(2), Duration.ZERO),
                    new BinanceClientProperties.CircuitBreaker(5, Duration.ofSeconds(30))),
            Clock.systemUTC(),
            JsonMapper.builder().build());

    @AfterEach
    void close() {
        client.close();
    }

    @Test
    void live_spotKlines_haveTheDocumentedShape() {
        List<Kline> klines = client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 2);

        assertThat(klines).hasSize(2);
        assertThat(klines.get(0).close()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void live_futuresPremiumIndex_hasTheDocumentedShape() {
        PremiumIndex index = client.premiumIndex("BTCUSDT");

        assertThat(index.markPrice()).isGreaterThan(BigDecimal.ZERO);
        assertThat(index.nextFundingTime()).isAfter(index.time());
    }
}
