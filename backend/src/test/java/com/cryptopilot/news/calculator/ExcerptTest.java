package com.cryptopilot.news.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The excerpt of BR-48: text only, entities decoded, at most 500 characters cut on a word.
 *
 * <p>Rule: BR-48; TECHNICAL_DESIGN 7.13.
 */
class ExcerptTest {

    @Test
    void BR48_theHtmlIsRemoved_andTheEntitiesDecoded() {
        assertThat(Excerpt.of("<p>Bitcoin traded <b>flat</b> &amp; ether fell &lt;2%&gt;.</p><script>x()</script>"))
                .isEqualTo("Bitcoin traded flat & ether fell <2%>.");
        assertThat(Excerpt.of("Plain text, no tags")).isEqualTo("Plain text, no tags");
    }

    @Test
    void BR48_aLongText_isCutOnAWord_atMost500CharactersWithTheEllipsis() {
        String words = "word ".repeat(150);

        String excerpt = Excerpt.of(words);

        assertThat(excerpt).hasSizeLessThanOrEqualTo(Excerpt.MAX_LENGTH).endsWith("word…");
        assertThat(excerpt).doesNotContain("wor…");
    }

    @Test
    void BR48_aTextOf500Characters_isKeptWhole() {
        String exact = "a".repeat(250) + " " + "b".repeat(249);

        assertThat(Excerpt.of(exact)).isEqualTo(exact);
    }

    @Test
    void BR48_aLongLastWord_isLeftOutWhole() {
        assertThat(Excerpt.of("x".repeat(400) + " " + "y".repeat(400))).isEqualTo("x".repeat(400) + "…");
    }

    @Test
    void BR48_oneEndlessWord_isCutWhereItMustBe() {
        assertThat(Excerpt.of("x".repeat(800))).isEqualTo("x".repeat(499) + "…");
        assertThat(Excerpt.of("ab " + "x".repeat(800)))
                .as("a space too early to cut on")
                .hasSize(Excerpt.MAX_LENGTH)
                .startsWith("ab x");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "<p> </p>", "<img src=\"x.png\">"})
    void BR48_noText_isNoExcerpt(String html) {
        assertThat(Excerpt.of(html)).isNull();
    }
}
