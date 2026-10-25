package com.cryptopilot.news.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The article text a summary is written from: the first body selector that holds text, with excluded blocks and
 * scripts removed, paragraphs kept apart, the charset read from the page, cut to the word budget.
 *
 * <p>Rule: BR-48; NSF-11; UC-49.
 */
class ArticleTextTest {

    private static final String PAGE = """
            <html><head><meta charset="windows-1252"><title>t</title><script>var x = "noise";</script></head>
            <body>
              <nav>Menu Markets Policy</nav>
              <div class="live-news-article"></div>
              <main id="content">
                <h2>Bitcoin steadies</h2>
                <p>Bitcoin traded flat near <b>$81,700</b> on Friday.</p>
                <aside class="related"><p>Related: Ether slides</p></aside>
                <ul><li>ETF outflows hit $635M</li><li>Oil at seven-month highs</li></ul>
                <blockquote><p>"Support is $80,000," a trader said.</p></blockquote>
                <figure><figcaption>Chart</figcaption></figure>
                <script>track()</script>
              </main>
            </body></html>""";

    @Test
    void NSF11_theFirstSelectorWithText_isRead_withoutTheExcludedBlocks() {
        String text = ArticleText.extract(
                        bytes(PAGE),
                        "https://e.com/a",
                        "div.live-news-article, main#content",
                        "figure, aside, .related, nav",
                        1200)
                .orElseThrow();

        assertThat(text)
                .isEqualTo("Bitcoin steadies\n\nBitcoin traded flat near $81,700 on Friday.\n\nETF outflows hit $635M"
                        + "\n\nOil at seven-month highs\n\n\"Support is $80,000,\" a trader said.");
        assertThat(text).doesNotContain("Related", "Chart", "track", "noise", "Menu");
    }

    @Test
    void NSF11_anElementWithoutBlocks_givesItsWholeText_andTheBudgetCutsIt() {
        String page = "<html><body><div class='body'>one two  three\nfour five</div></body></html>";

        assertThat(ArticleText.extract(bytes(page), "https://e.com/a", "div.body", null, 3))
                .contains("one two three");
        assertThat(ArticleText.extract(bytes(page), "https://e.com/a", "div.body", " ", 100))
                .contains("one two three four five");
    }

    @Test
    void NSF11_noSelector_orNoMatch_orOnlyEmptyMatches_isNoText() {
        assertThat(ArticleText.extract(bytes(PAGE), "https://e.com/a", null, null, 100))
                .isEmpty();
        assertThat(ArticleText.extract(bytes(PAGE), "https://e.com/a", " ", null, 100))
                .isEmpty();
        assertThat(ArticleText.extract(bytes(PAGE), "https://e.com/a", "article.story", null, 100))
                .isEmpty();
        assertThat(ArticleText.extract(bytes(PAGE), "https://e.com/a", "div.live-news-article", null, 100))
                .isEmpty();
    }

    @Test
    void NSF11_theCharsetOfThePage_isHonoured() {
        byte[] latin1 = "<html><head><meta charset=\"iso-8859-1\"></head><body><p>Café résumé</p></body></html>"
                .getBytes(StandardCharsets.ISO_8859_1);

        assertThat(ArticleText.extract(latin1, "https://e.com/a", "p", null, 10))
                .contains("Café résumé");
    }

    @Test
    void NSF11_aSelectorList_isSplitOnlyAtTheTopLevel() {
        assertThat(ArticleText.alternatives("div.a, main#b ,  , :is(p, li), a[title='x, y']"))
                .containsExactly("div.a", "main#b", ":is(p, li)", "a[title='x, y']");
    }

    @Test
    void NSF11_wordsAreCountedBetweenSpaces() {
        assertThat(ArticleText.words("  Bitcoin  giảm\n3,5% ")).isEqualTo(3);
        assertThat(ArticleText.words(" ")).isZero();
        assertThat(ArticleText.words(null)).isZero();
    }

    private static byte[] bytes(String html) {
        return html.getBytes(StandardCharsets.UTF_8);
    }
}
