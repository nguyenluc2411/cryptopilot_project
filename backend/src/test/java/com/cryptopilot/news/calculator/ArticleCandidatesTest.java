package com.cryptopilot.news.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.news.model.ArticleCandidate;
import com.cryptopilot.news.model.CoinTag;
import com.cryptopilot.news.model.TaggableCoin;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A feed item becomes the article BR-48 allows: text title, original address with its normalized hash, title hash,
 * excerpt, a publish time never in the future, and its coins; an item without a headline or a web address does not.
 *
 * <p>Rule: BR-48; NSF-10; TECHNICAL_DESIGN 7.13.
 */
class ArticleCandidatesTest {

    private static final Instant COLLECTED = Instant.parse("2026-10-25T10:00:00Z");
    private static final UUID BTC = UUID.fromString("019b76da-a800-7c01-8000-000000000001");
    private static final CoinTagger TAGGER = CoinTagger.of(List.of(new TaggableCoin(BTC, "BTC", List.of("Bitcoin"))));

    @Test
    void BR48_anItem_becomesAnArticleWithOnlyWhatMayBeKept() {
        ArticleCandidate article = candidate(
                        " Bitcoin &amp; the <b>Fed</b> ",
                        "https://Example.com/btc?utm_source=rss",
                        COLLECTED.minusSeconds(3600),
                        "<p>BTC held its ground.</p>")
                .orElseThrow();

        assertThat(article.title()).isEqualTo("Bitcoin & the Fed");
        assertThat(article.originalUrl()).isEqualTo("https://Example.com/btc?utm_source=rss");
        assertThat(article.urlHash()).isEqualTo(ContentHash.sha256("https://example.com/btc"));
        assertThat(article.titleHash()).isEqualTo(ContentHash.sha256("bitcoin the fed"));
        assertThat(article.excerpt()).isEqualTo("BTC held its ground.");
        assertThat(article.publishedAt()).isEqualTo(COLLECTED.minusSeconds(3600));
        assertThat(article.coins()).containsExactly(new CoinTag(BTC, true));
    }

    @Test
    void BR48_theSameArticleBehindTwoAddresses_hasOneAddressHash() {
        String first = candidate("A", "https://example.com/a?utm_medium=x#top", null, null)
                .orElseThrow()
                .urlHash();
        String second = candidate("A", "HTTPS://EXAMPLE.COM:443/a", null, null)
                .orElseThrow()
                .urlHash();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void NSF10_aMissingOrFuturePublishTime_isTheCollectionTime() {
        assertThat(candidate("A", "https://e.com/a", null, null).orElseThrow().publishedAt())
                .isEqualTo(COLLECTED);
        assertThat(candidate("A", "https://e.com/a", COLLECTED.plusSeconds(7200), null)
                        .orElseThrow()
                        .publishedAt())
                .isEqualTo(COLLECTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "<b></b>"})
    void NSF10_anItemWithoutAHeadline_isNoArticle(String title) {
        assertThat(candidate(title, "https://e.com/a", null, null)).isEmpty();
        assertThat(candidate(null, "https://e.com/a", null, null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ftp://e.com/a", "/relative", "javascript:void(0)"})
    void NSF10_anItemWithoutAWebAddress_isNoArticle(String link) {
        assertThat(candidate("Title", link, null, null)).isEmpty();
        assertThat(candidate("Title", null, null, null)).isEmpty();
    }

    @Test
    void BR48_aTitleOrAnAddressLongerThanItsColumn_isFittedOrRefused() {
        assertThat(candidate("t".repeat(600), "https://e.com/a", null, null)
                        .orElseThrow()
                        .title())
                .hasSize(ArticleCandidates.MAX_TITLE_LENGTH);
        String trackedLong = "https://e.com/a?" + "utm_x=" + "1".repeat(2100);
        assertThat(candidate("T", trackedLong, null, null).orElseThrow().originalUrl())
                .as("too long as written, short once normalized")
                .isEqualTo("https://e.com/a");
        assertThat(candidate("T", "https://e.com/" + "p".repeat(2100), null, null))
                .isEmpty();
    }

    @Test
    void D125_theFeedsImage_isKeptWhenItIsAWebAddress_madeAbsoluteAgainstTheArticle() {
        assertThat(withImage("https://img.example.com/a.jpg").imageUrl()).isEqualTo("https://img.example.com/a.jpg");
        assertThat(withImage("/covers/a.jpg").imageUrl()).isEqualTo("https://example.com/covers/a.jpg");
        assertThat(withImage("javascript:alert(1)").imageUrl()).isNull();
        assertThat(withImage("data:image/png;base64,AAAA").imageUrl()).isNull();
        assertThat(withImage("https://img.example.com/" + "a".repeat(2048)).imageUrl())
                .isNull();
        assertThat(withImage(null).imageUrl()).isNull();
        assertThat(candidate("Bitcoin holds", "https://example.com/a", null, null)
                        .orElseThrow()
                        .imageUrl())
                .isNull();
    }

    @Test
    void NSF10_theCategoryFilter_keepsOnlyItemsThatCarryOneOfItsValues() {
        List<String> markets = List.of("Markets", "https://www.coindesk.com/markets");

        assertThat(ArticleCandidates.passes(null, markets)).isTrue();
        assertThat(ArticleCandidates.passes("  ", List.of())).isTrue();
        assertThat(ArticleCandidates.passes(" , ", List.of())).isTrue();
        assertThat(ArticleCandidates.passes("https://www.coindesk.com/markets", markets))
                .isTrue();
        assertThat(ArticleCandidates.passes("policy, MARKETS", markets)).isTrue();
        assertThat(ArticleCandidates.passes("policy", markets)).isFalse();
        assertThat(ArticleCandidates.passes("markets", List.of())).isFalse();
    }

    private static ArticleCandidate withImage(String imageUrl) {
        return ArticleCandidates.from(
                        "Bitcoin holds", "https://example.com/news/a", null, null, imageUrl, COLLECTED, TAGGER)
                .orElseThrow();
    }

    private static Optional<ArticleCandidate> candidate(
            String title, String link, Instant publishedAt, String description) {
        return ArticleCandidates.from(title, link, publishedAt, description, COLLECTED, TAGGER);
    }
}
