package com.cryptopilot.market.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A coin takes its name and logo from a directory only while it lacks them, and never loses one already set.
 *
 * <p>Rule: SRS 3.1.5.
 */
class CoinTest {

    private static final String LOGO = "https://static.coinpaprika.com/coin/btc-bitcoin/logo.png";

    @Test
    void aCoinFromTheExchangeIsNamedAfterItsSymbolAndHasNoLogo() {
        Coin coin = Coin.fromExchange("BTC");

        assertThat(coin.getCoinName()).isEqualTo("BTC");
        assertThat(coin.getLogoUrl()).isNull();
    }

    @Test
    void describingFillsInTheNameAndTheLogo() {
        Coin coin = Coin.fromExchange("BTC");

        assertThat(coin.describe("Bitcoin", LOGO)).isTrue();

        assertThat(coin.getCoinName()).isEqualTo("Bitcoin");
        assertThat(coin.getLogoUrl()).isEqualTo(LOGO);
    }

    @Test
    void describingAgainOverwritesNothing() {
        Coin coin = Coin.fromExchange("BTC");
        coin.describe("Bitcoin", LOGO);

        assertThat(coin.describe("Bitcoin Fake", "https://elsewhere.example/b.png"))
                .isFalse();

        assertThat(coin.getCoinName()).isEqualTo("Bitcoin");
        assertThat(coin.getLogoUrl()).isEqualTo(LOGO);
    }

    @Test
    void aLogoThatIsNotHttpsOrTooLongIsIgnored() {
        Coin coin = Coin.fromExchange("BTC");

        assertThat(coin.describe(null, "http://insecure.example/b.png")).isFalse();
        assertThat(coin.describe(null, "https://x.example/" + "a".repeat(Coin.LOGO_URL_LENGTH)))
                .isFalse();
        assertThat(coin.getLogoUrl()).isNull();
    }

    @Test
    void aBlankOrTooLongNameIsIgnored() {
        Coin coin = Coin.fromExchange("BTC");

        assertThat(coin.describe("   ", null)).isFalse();
        assertThat(coin.describe("n".repeat(Coin.NAME_LENGTH + 1), null)).isFalse();
        assertThat(coin.getCoinName()).isEqualTo("BTC");
    }

    @Test
    void aLogoAloneLeavesTheNameAsItIs() {
        Coin coin = Coin.fromExchange("1000PEPE");

        assertThat(coin.describe(null, LOGO)).isTrue();

        assertThat(coin.getCoinName()).isEqualTo("1000PEPE");
        assertThat(coin.getLogoUrl()).isEqualTo(LOGO);
    }
}
