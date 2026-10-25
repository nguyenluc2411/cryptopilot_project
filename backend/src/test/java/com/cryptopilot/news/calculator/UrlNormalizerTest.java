package com.cryptopilot.news.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The normalized address of TECHNICAL_DESIGN 7.13: two spellings of one page meet, two pages never do.
 *
 * <p>Rule: BR-48, UC-49; TECHNICAL_DESIGN 7.13.
 */
class UrlNormalizerTest {

    @ParameterizedTest
    @CsvSource({
        "HTTPS://News.Example.COM/a/b, https://news.example.com/a/b",
        "https://example.com:443/a, https://example.com/a",
        "http://example.com:80/a, http://example.com/a",
        "https://example.com:8443/a, https://example.com:8443/a",
        "https://example.com/a#comments, https://example.com/a",
        "https://example.com, https://example.com/",
        "'  https://example.com/a  ', https://example.com/a",
        "https://example.com/a b, https://example.com/a%20b"
    })
    void BR48_theSchemeHostPortAndFragment_areNormalized(String address, String expected) {
        assertThat(UrlNormalizer.normalize(address)).contains(expected);
    }

    @Test
    void BR48_trackingParameters_areRemoved_andTheOthersSorted() {
        assertThat(UrlNormalizer.normalize(
                        "https://example.com/a?z=1&utm_source=x&UTM_Medium=y&fbclid=f&gclid=g&a=2&_ga=1&mc_cid=c"))
                .contains("https://example.com/a?a=2&z=1");
        assertThat(UrlNormalizer.normalize("https://example.com/a?utm_campaign=only"))
                .contains("https://example.com/a");
        assertThat(UrlNormalizer.normalize("https://example.com/a?&&b=1&")).contains("https://example.com/a?b=1");
    }

    @Test
    void BR48_theSameArticleSpelledTwoWays_normalizesToOneAddress() {
        assertThat(UrlNormalizer.normalize("https://Example.com/news/btc?id=7&utm_source=rss#top"))
                .isEqualTo(UrlNormalizer.normalize("https://example.com:443/news/btc?id=7"));
    }

    @Test
    void BR48_thePathKeepsItsCaseAndTrailingSlash_becauseTheyMayNameAnotherPage() {
        assertThat(UrlNormalizer.normalize("https://example.com/News/")).contains("https://example.com/News/");
        assertThat(UrlNormalizer.normalize("https://example.com/News"))
                .isNotEqualTo(UrlNormalizer.normalize("https://example.com/News/"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "   ",
                "ftp://example.com/feed",
                "javascript:alert(1)",
                "file:///etc/passwd",
                "/relative/path",
                "example.com/feed",
                "https:///no-host",
                "https://user:secret@example.com/feed",
                "https://example.com/%zz^bad"
            })
    void UC49_anythingButAnAbsoluteWebAddressWithAHost_isRefused(String address) {
        assertThat(UrlNormalizer.normalize(address)).isEmpty();
    }
}
