package com.cryptopilot.news.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The cover image of an article by its address (D-129): the page's preview image when the feed gave none, and only an
 * absolute web address that fits the column.
 *
 * <p>Rule: D-129; BR-48.
 */
class ArticleImageTest {

    private static final String PAGE_URL = "https://news.example.com/markets/btc";

    @Test
    void D125_thePagesOgImage_isTaken_beforeItsTwitterImage() {
        String page = """
                <html><head>
                <meta name="twitter:image" content="https://cdn.example.com/twitter.jpg">
                <meta property="og:image" content="https://cdn.example.com/og.jpg">
                </head><body></body></html>""";

        assertThat(ArticleImage.fromPage(bytes(page), PAGE_URL)).contains("https://cdn.example.com/og.jpg");
    }

    @Test
    void D125_aRelativePreview_isMadeAbsolute_andAnUnusableOne_isSkipped() {
        String page = """
                <html><head>
                <meta property="og:image" content="javascript:alert(1)">
                <meta name="twitter:image" content="/img/cover.png">
                </head></html>""";

        assertThat(ArticleImage.fromPage(bytes(page), PAGE_URL)).contains("https://news.example.com/img/cover.png");
    }

    @Test
    void D125_aPageWithoutAPreview_hasNoImage() {
        assertThat(ArticleImage.fromPage(bytes("<html><body><img src=\"/x.jpg\"></body></html>"), PAGE_URL))
                .isEmpty();
    }

    @Test
    void D125_onlyAnAbsoluteWebAddressThatFitsTheColumn_isKept() {
        assertThat(ArticleImage.of(" https://cdn.example.com/a b.jpg ", null))
                .contains("https://cdn.example.com/a%20b.jpg");
        assertThat(ArticleImage.of("ftp://cdn.example.com/a.jpg", null)).isEmpty();
        assertThat(ArticleImage.of("/a.jpg", null)).isEmpty();
        assertThat(ArticleImage.of("https://", null)).isEmpty();
        assertThat(ArticleImage.of("  ", PAGE_URL)).isEmpty();
        assertThat(ArticleImage.of("https://cdn.example.com/" + "a".repeat(2048), null))
                .isEmpty();
    }

    private static byte[] bytes(String page) {
        return page.getBytes(StandardCharsets.UTF_8);
    }
}
