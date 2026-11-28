package com.cryptopilot.market.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.market.model.ReplayScenario.Point;
import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * RF-07: a scenario's path is its points joined by straight lines, the first price before the start and the last one
 * held after the end; a malformed scenario is refused with its name.
 */
class ReplayScenarioTest {

    private static final ReplayScenario DIP = new ReplayScenario(
            "btc_near_liq",
            "dip and rebound",
            MarketType.FUTURES,
            "BTCUSDT",
            1000,
            new BigDecimal("0.1"),
            List.of(point(0, "60000.0"), point(40, "59150.0"), point(80, "60200.0")));

    @ParameterizedTest(name = "{0} ms after the start: {1}")
    @CsvSource({
        "-1000, 60000.0",
        "0, 60000.0",
        "20000, 59575.00",
        "39999, 59150.02",
        "40000, 59150.00",
        "60000, 59675.00",
        "80000, 60200.0",
        "999999, 60200.0"
    })
    void RF07_thePrice_followsTheLinesBetweenThePoints(long millis, String expected) {
        assertThat(DIP.priceAt(Duration.ofMillis(millis))).isEqualByComparingTo(expected);
    }

    @Test
    void RF07_aScenario_knowsItsLengthAndStep() {
        assertThat(DIP.length()).isEqualTo(Duration.ofSeconds(80));
        assertThat(DIP.step()).isEqualTo(Duration.ofSeconds(1));
        assertThat(new ReplayScenario(
                                "flat",
                                null,
                                MarketType.SPOT,
                                "ETHUSDT",
                                200,
                                BigDecimal.ONE,
                                List.of(point(0, "3010")))
                        .description())
                .isEmpty();
    }

    @Test
    void RF07_aMalformedScenario_isRefused() {
        List<Point> ok = List.of(point(0, "1"));
        BigDecimal spread = BigDecimal.ONE;
        MarketType spot = MarketType.SPOT;

        assertThatThrownBy(() -> new ReplayScenario("Bad-Name", "", spot, "BTCUSDT", 1000, spread, ok))
                .hasMessageContaining("name");
        assertThatThrownBy(() -> new ReplayScenario(null, "", spot, "BTCUSDT", 1000, spread, ok))
                .hasMessageContaining("name");
        assertThatThrownBy(() -> new ReplayScenario("x", "", null, "BTCUSDT", 1000, spread, ok))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReplayScenario("x", "", spot, "btc", 1000, spread, ok))
                .hasMessageContaining("symbol");
        assertThatThrownBy(() -> new ReplayScenario("x", "", spot, null, 1000, spread, ok))
                .hasMessageContaining("symbol");
        assertThatThrownBy(() -> new ReplayScenario("x", "", spot, "BTCUSDT", 199, spread, ok))
                .hasMessageContaining("stepMillis");
        assertThatThrownBy(() -> new ReplayScenario("x", "", spot, "BTCUSDT", 10_001, spread, ok))
                .hasMessageContaining("stepMillis");
        assertThatThrownBy(() -> new ReplayScenario("x", "", spot, "BTCUSDT", 1000, BigDecimal.ZERO, ok))
                .hasMessageContaining("spread");
        assertThatThrownBy(() -> new ReplayScenario("x", "", spot, "BTCUSDT", 1000, null, ok))
                .hasMessageContaining("spread");
        assertThatThrownBy(() -> new ReplayScenario("x", "", spot, "BTCUSDT", 1000, spread, List.of()))
                .hasMessageContaining("at least one point");
        assertThatThrownBy(() -> new ReplayScenario("x", "", spot, "BTCUSDT", 1000, spread, null))
                .hasMessageContaining("at least one point");
        assertThatThrownBy(() -> new ReplayScenario("x", "", spot, "BTCUSDT", 1000, spread, List.of(point(5, "1"))))
                .hasMessageContaining("second 0");
        assertThatThrownBy(() -> new ReplayScenario(
                        "x", "", spot, "BTCUSDT", 1000, spread, List.of(point(0, "1"), point(0, "2"))))
                .hasMessageContaining("later");
        assertThatThrownBy(() -> new ReplayScenario("x", "", spot, "BTCUSDT", 1000, spread, List.of(point(0, "0"))))
                .hasMessageContaining("above zero");
        assertThatThrownBy(
                        () -> new ReplayScenario("x", "", spot, "BTCUSDT", 1000, spread, List.of(new Point(0, null))))
                .hasMessageContaining("above zero");
    }

    private static Point point(long at, String price) {
        return new Point(at, new BigDecimal(price));
    }
}
