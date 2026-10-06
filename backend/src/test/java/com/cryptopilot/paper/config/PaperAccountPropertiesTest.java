package com.cryptopilot.paper.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The grant of a paper account is checked when the application starts: symbols as the coin table stores them, an
 * environment variable that overrides a configured amount, the order of the configuration, amounts that fit
 * {@code numeric(28,8)}, and USDT alone in the USDⓈ-M Futures wallet.
 *
 * <p>Rule: TR-04; Q-T5.
 */
class PaperAccountPropertiesTest {

    private static final Map<String, BigDecimal> FUTURES = Map.of("usdt", new BigDecimal("5000"));

    @Test
    void aSymbolBoundFromAnEnvironmentVariable_isUpperCased() {
        // CRYPTOPILOT_PAPER_ACCOUNT_SPOTGRANT_USDT binds its key as "usdt".
        PaperAccountProperties properties =
                new PaperAccountProperties(Map.of("usdt", BigDecimal.TEN), Map.of("usdt", BigDecimal.ONE));

        assertThat(properties.spotGrant()).containsOnlyKeys("USDT");
        assertThat(properties.futuresGrant()).containsOnlyKeys("USDT");
    }

    /**
     * application.yml writes {@code USDT}, the operator sets {@code CRYPTOPILOT_PAPER_ACCOUNT_SPOTGRANT_USDT=20000}:
     * Spring keeps both keys, and the environment's value is the one granted, in either order.
     */
    @Test
    void anEnvironmentOverride_ofAKeyConfiguredInAnotherCase_winsAndDoesNotStopTheApplication() {
        Map<String, BigDecimal> fileFirst = new LinkedHashMap<>();
        fileFirst.put("USDT", new BigDecimal("10000"));
        fileFirst.put("usdt", new BigDecimal("20000"));
        Map<String, BigDecimal> environmentFirst = new LinkedHashMap<>();
        environmentFirst.put("usdt", new BigDecimal("20000"));
        environmentFirst.put("USDT", new BigDecimal("10000"));
        Map<String, BigDecimal> futures = new LinkedHashMap<>();
        futures.put("USDT", new BigDecimal("5000"));
        futures.put("usdt", new BigDecimal("7000"));

        assertThat(new PaperAccountProperties(fileFirst, futures).spotGrant())
                .containsExactly(Map.entry("USDT", new BigDecimal("20000")));
        assertThat(new PaperAccountProperties(environmentFirst, futures).spotGrant())
                .containsExactly(Map.entry("USDT", new BigDecimal("20000")));
        assertThat(new PaperAccountProperties(fileFirst, futures).futuresGrant())
                .containsExactly(Map.entry("USDT", new BigDecimal("7000")));
    }

    @Test
    void theGrant_keepsTheOrderItIsConfiguredIn() {
        Map<String, BigDecimal> spot = new LinkedHashMap<>();
        for (String symbol : new String[] {"usdt", "btc", "eth", "bnb", "sol", "xrp", "ada"}) {
            spot.put(symbol, BigDecimal.ONE);
        }

        assertThat(new PaperAccountProperties(spot, FUTURES).spotGrant().keySet())
                .containsExactly("USDT", "BTC", "ETH", "BNB", "SOL", "XRP", "ADA");
    }

    @Test
    void theFuturesWallet_isGrantedUsdtOnly() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new PaperAccountProperties(Map.of("usdt", BigDecimal.TEN), Map.of("btc", BigDecimal.ONE)))
                .withMessageContaining("USDT only");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaperAccountProperties(
                        Map.of("usdt", BigDecimal.TEN), Map.of("usdt", BigDecimal.ONE, "bnb", BigDecimal.ONE)));
    }

    @Test
    void anAmount_fitsNumeric28Scale8() {
        assertThat(new PaperAccountProperties(Map.of("btc", new BigDecimal("0.12345678")), FUTURES).spotGrant())
                .containsEntry("BTC", new BigDecimal("0.12345678"));
        assertThat(new PaperAccountProperties(Map.of("usdt", new BigDecimal("99999999999999999999.00000000")), FUTURES)
                        .spotGrant())
                .containsKey("USDT");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaperAccountProperties(Map.of("btc", new BigDecimal("0.123456789")), FUTURES))
                .withMessageContaining("8 decimals");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaperAccountProperties(Map.of("usdt", new BigDecimal("1e21")), FUTURES))
                .withMessageContaining("20 integer digits");
    }

    @Test
    void anInvalidSymbolOrAmount_isRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaperAccountProperties(Map.of("us dt", BigDecimal.TEN), FUTURES));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaperAccountProperties(Map.of("usdt", BigDecimal.ZERO), FUTURES));
        assertThatIllegalArgumentException().isThrownBy(() -> new PaperAccountProperties(Map.of(), FUTURES));
        assertThatNullPointerException().isThrownBy(() -> new PaperAccountProperties(null, FUTURES));
    }
}
