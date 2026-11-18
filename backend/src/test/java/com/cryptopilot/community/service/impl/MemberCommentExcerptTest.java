package com.cryptopilot.community.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The start of a post's text that a row of the Comments tab shows (D-99): the whole text when short, otherwise cut at
 * a word within {@value MemberServiceImpl#EXCERPT_LENGTH} characters, or at the limit itself when the text has no
 * break near it; line breaks and runs of spaces read as one space.
 */
class MemberCommentExcerptTest {

    @Test
    void aShortText_isShownWhole_withItsSpacesFolded() {
        assertThat(MemberServiceImpl.excerpt("  Watching\n\nthe   range  ")).isEqualTo("Watching the range");
    }

    @Test
    void aTextOfExactlyTheLimit_isShownWhole() {
        String text = "x".repeat(MemberServiceImpl.EXCERPT_LENGTH);

        assertThat(MemberServiceImpl.excerpt(text)).isEqualTo(text);
    }

    @Test
    void aLongText_isCutAtTheLastWordThatFits() {
        String text = "word ".repeat(40);

        String excerpt = MemberServiceImpl.excerpt(text);

        assertThat(excerpt).endsWith("word…").hasSizeLessThanOrEqualTo(MemberServiceImpl.EXCERPT_LENGTH + 1);
    }

    @Test
    void anEmojiAcrossTheLimit_isLeftOutWhole_neverCutInHalf() {
        String text = "y".repeat(MemberServiceImpl.EXCERPT_LENGTH - 1) + "😀" + "z".repeat(50);

        String excerpt = MemberServiceImpl.excerpt(text);

        assertThat(excerpt).isEqualTo("y".repeat(MemberServiceImpl.EXCERPT_LENGTH - 1) + "…");
    }

    @Test
    void aLongTextWithoutABreakNearTheLimit_isCutAtTheLimit() {
        String text = "short " + "y".repeat(300);

        assertThat(MemberServiceImpl.excerpt(text))
                .hasSize(MemberServiceImpl.EXCERPT_LENGTH + 1)
                .endsWith("y…");
    }
}
