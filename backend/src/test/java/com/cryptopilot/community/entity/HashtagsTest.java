package com.cryptopilot.community.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Hashtags read from a text as CoinMarketCap Community reads them.
 *
 * <p>Rule: D-99.
 */
class HashtagsTest {

    @Test
    void D99_hashtagsAreLowerCase_withoutTheHash_inTheOrderWritten() {
        assertThat(Hashtags.of("Watching #BTC and #Halving2028", "more on #btc and #DeFi_Summer"))
                .containsExactly("btc", "halving2028", "defi_summer");
    }

    @Test
    void D99_aHashtagMayBeVietnamese() {
        assertThat(Hashtags.of("Góc nhìn #tiềnảo #ThịTrường")).containsExactly("tiềnảo", "thịtrường");
    }

    @Test
    void D99_whatIsNotAHashtag_isLeftOut() {
        assertThat(Hashtags.of("a#b, #1, #12345, # alone, #x, mail@site#tag, ##double"))
                .containsExactly("double");
        assertThat(Hashtags.of("#" + "a".repeat(51))).isEmpty();
        assertThat(Hashtags.of((String) null)).isEmpty();
    }

    @Test
    void D99_aPostCarriesAtMostTenHashtags() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            text.append(" #tag").append((char) ('a' + i));
        }
        assertThat(Hashtags.of(text.toString())).hasSize(Hashtags.MAX_PER_POST).startsWith("taga");
    }

    @Test
    void D99_aFilter_namesAHashtagWithOrWithoutItsHash() {
        assertThat(Hashtags.normalized(" #BTC ")).isEqualTo("btc");
        assertThat(Hashtags.normalized("Btc")).isEqualTo("btc");
    }
}
