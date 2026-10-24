package com.cryptopilot.news.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

/**
 * The normalized title and the hashes of TECHNICAL_DESIGN 7.13: one story told with other punctuation, case or
 * compatibility characters has one title hash; another story has another.
 *
 * <p>Rule: BR-48; TECHNICAL_DESIGN 7.13.
 */
class TitleNormalizerTest {

    @Test
    void BR48_caseAndPunctuationAndSpacing_doNotMakeAnotherStory() {
        String cointelegraph = "Bitcoin steadies near $81,700 as altcoins rebound — PEPE climbs 13%!";
        String rewritten = "  bitcoin STEADIES near 81 700, as altcoins rebound: pepe climbs 13 ";

        assertThat(TitleNormalizer.normalize(cointelegraph))
                .isEqualTo("bitcoin steadies near 81 700 as altcoins rebound pepe climbs 13");
        assertThat(TitleNormalizer.normalize(rewritten)).isEqualTo(TitleNormalizer.normalize(cointelegraph));
        assertThat(ContentHash.sha256(TitleNormalizer.normalize(rewritten)))
                .isEqualTo(ContentHash.sha256(TitleNormalizer.normalize(cointelegraph)));
    }

    @Test
    void BR48_compatibilityCharacters_foldUnderNfkc() {
        assertThat(TitleNormalizer.normalize("ＢＴＣ ＥＴＦ ﬂows")).isEqualTo("btc etf flows");
    }

    @Test
    void BR48_anotherStory_hasAnotherHash() {
        assertThat(ContentHash.sha256(TitleNormalizer.normalize("Bitcoin slides below $82,000")))
                .isNotEqualTo(ContentHash.sha256(TitleNormalizer.normalize("Bitcoin slides below $81,000")));
    }

    @Test
    void BR48_theHash_isTheSha256OfTheText_asTheColumnHoldsIt() {
        assertThat(ContentHash.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
                .hasSize(64);
        assertThatNullPointerException().isThrownBy(() -> ContentHash.sha256(null));
        assertThatNullPointerException().isThrownBy(() -> TitleNormalizer.normalize(null));
    }
}
