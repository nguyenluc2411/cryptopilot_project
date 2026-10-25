package com.cryptopilot.news.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.ai.AiServiceException;
import com.cryptopilot.ai.NewsSummaryApi;
import com.cryptopilot.ai.NewsSummaryRequest;
import com.cryptopilot.ai.NewsSummaryResult;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.news.client.RssClient;
import com.cryptopilot.news.client.RssClientException;
import com.cryptopilot.news.client.WebPage;
import com.cryptopilot.news.config.NewsSummaryProperties;
import com.cryptopilot.news.entity.NewsArticle;
import com.cryptopilot.news.entity.NewsSource;
import com.cryptopilot.news.model.NewsSourceSettings;
import com.cryptopilot.news.model.SummaryRun;
import com.cryptopilot.news.repository.NewsArticleRepository;
import com.cryptopilot.news.repository.NewsSourceRepository;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NSF-11 without a database or an AI Service: what the model is given, what is stored, when it is asked again, and when
 * a run stops.
 *
 * <p>Rule: NSF-11; BR-48; the hard scope boundary (no investment advice).
 */
class NewsSummaryServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final String BODY = "<html><body><main><p>Bitcoin traded flat near $81,700 on Friday as ETF"
            + " outflows reached $635 million.</p></main></body></html>";

    private final NewsArticleRepository articles = mock(NewsArticleRepository.class);
    private final NewsSourceRepository sources = mock(NewsSourceRepository.class);
    private final RssClient rss = mock(RssClient.class);
    private final NewsSummaryApi ai = mock(NewsSummaryApi.class);
    private NewsSource source;

    @BeforeEach
    void setUp() {
        source = NewsSource.create(
                new NewsSourceSettings("CoinDesk Markets", "https://e.com/feed", 15, true, null, "main", null, "UA/1"),
                null);
        when(sources.findById(source.getId())).thenReturn(Optional.of(source));
        when(rss.readPage(any(), any()))
                .thenReturn(new WebPage("https://e.com/a", BODY.getBytes(StandardCharsets.UTF_8), "text/html"));
    }

    @Test
    void NSF11_nothingWithoutASummary_sendsNothing() {
        when(articles.findUnsummarized(any(), anyInt(), anyInt())).thenReturn(List.of());

        assertThat(service(properties(10)).summarizeDue()).isEqualTo(SummaryRun.NOTHING_DUE);
        verifyNoInteractions(ai, rss);
    }

    @Test
    void NSF11_theModelReadsTheArticleItself_andASoundAnswerIsStored() {
        NewsArticle article = article("Bitcoin holds $81K", "Excerpt only.");
        when(articles.findUnsummarized(NOW.minus(Duration.ofDays(7)), 3, 10)).thenReturn(List.of(article));
        when(ai.summarize(any())).thenReturn(answer("Bitcoin đi ngang quanh $81,700; ETF rút $635 triệu.", "BEARISH"));

        SummaryRun run = service(properties(30)).summarizeDue();

        assertThat(run).isEqualTo(new SummaryRun(1, 1, 0));
        NewsSummaryRequest sent = sent(1).getFirst();
        assertThat(sent.model()).isEqualTo("test-model");
        assertThat(sent.systemPrompt()).isEqualTo("Write in Vietnamese, at most 30 words.");
        assertThat(sent.temperature()).isEqualTo(0.2);
        assertThat(sent.maxOutputTokens()).isEqualTo(512);
        assertThat(sent.title()).isEqualTo("Bitcoin holds $81K");
        assertThat(sent.source()).isEqualTo("CoinDesk Markets");
        assertThat(sent.text())
                .isEqualTo("Bitcoin traded flat near $81,700 on Friday as ETF outflows reached $635" + " million.");
        assertThat(sent.feedback()).isNull();
        verify(rss).readPage(article.getOriginalUrl(), "UA/1");
        verify(articles)
                .saveSummary(
                        article.getId(),
                        "Tiêu đề.",
                        "Bitcoin đi ngang quanh $81,700; ETF rút $635 triệu.",
                        "BEARISH",
                        NOW);
    }

    @Test
    void D125_theBodyAndThePagesCoverImage_areStoredWithTheSummary() {
        NewsArticle article = article("Bitcoin holds $81K", "Excerpt only.");
        when(articles.findUnsummarized(any(), anyInt(), anyInt())).thenReturn(List.of(article));
        when(rss.readPage(any(), any())).thenReturn(page("/covers/btc.jpg"));
        when(articles.saveSummary(any(), any(), any(), any(), any())).thenReturn(1);
        when(ai.summarize(any()))
                .thenReturn(answerWithBody("  Bitcoin đi ngang quanh $81,700.\n\nETF bị rút $635 million.  "));

        assertThat(service(properties(30)).summarizeDue()).isEqualTo(new SummaryRun(1, 1, 0));

        verify(articles)
                .saveArticle(
                        article.getId(),
                        "Bitcoin đi ngang quanh $81,700.\n\nETF bị rút $635 million.",
                        "https://e.com/covers/btc.jpg");
    }

    @Test
    void D125_aBodyThatStillFailsAfterTheRetry_isDropped_andTheSummaryKept() {
        NewsArticle article = article("Bitcoin holds $81K", "Excerpt only.");
        when(articles.findUnsummarized(any(), anyInt(), anyInt())).thenReturn(List.of(article));
        when(articles.saveSummary(any(), any(), any(), any(), any())).thenReturn(1);
        when(ai.summarize(any())).thenReturn(answerWithBody("Bitcoin có thể lên $95,000."));

        assertThat(service(properties(30)).summarizeDue()).isEqualTo(new SummaryRun(1, 1, 0));

        assertThat(sent(2).get(1).feedback()).isEqualTo("these figures of the body are not in the article: 95,000");
        verify(articles).saveSummary(eq(article.getId()), any(), eq("Bitcoin đi ngang."), eq("NEUTRAL"), eq(NOW));
        verify(articles).saveArticle(article.getId(), null, null);
    }

    @Test
    void D125_anArticleWithACoverImage_keepsIt_andNothingIsWrittenWhenAnotherRunStoredFirst() throws Exception {
        NewsArticle article = article("Bitcoin holds $81K", "Excerpt only.");
        set(article, "imageUrl", "https://img.example.com/feed.jpg");
        NewsArticle raced = article("Ether steady", "Excerpt.");
        when(articles.findUnsummarized(any(), anyInt(), anyInt())).thenReturn(List.of(article, raced));
        when(rss.readPage(any(), any())).thenReturn(page("https://e.com/og.jpg"));
        when(articles.saveSummary(eq(article.getId()), any(), any(), any(), any()))
                .thenReturn(1);
        when(ai.summarize(any())).thenReturn(answerWithBody("Bitcoin đi ngang."));

        service(properties(30)).summarizeDue();

        verify(articles).saveArticle(article.getId(), "Bitcoin đi ngang.", null);
        verify(articles, never()).saveArticle(eq(raced.getId()), any(), any());
    }

    @Test
    void NSF11_withoutABody_theExcerptIsSummarized() {
        NewsArticle unreadable = article("Page down", "Bitcoin rose 2% today.");
        NewsArticle tooShort = article("Paywalled", "Ether fell 4%.");
        NewsArticle bare = article("Only a title 5%", null);
        when(articles.findUnsummarized(any(), anyInt(), anyInt())).thenReturn(List.of(unreadable, tooShort, bare));
        when(rss.readPage(eq(unreadable.getOriginalUrl()), any()))
                .thenThrow(new RssClientException(RssClientException.Reason.HTTP_STATUS, "403"));
        when(rss.readPage(eq(tooShort.getOriginalUrl()), any()))
                .thenReturn(new WebPage(tooShort.getOriginalUrl(), "<main><p>Subscribe</p></main>".getBytes(), null));
        when(rss.readPage(eq(bare.getOriginalUrl()), any()))
                .thenReturn(new WebPage(bare.getOriginalUrl(), "<p>no main</p>".getBytes(), null));
        when(ai.summarize(any())).thenReturn(answer("Tóm tắt.", "NEUTRAL"));

        assertThat(service(properties(10, 5)).summarizeDue().summarized()).isEqualTo(3);

        assertThat(sent(3))
                .extracting(NewsSummaryRequest::text)
                .containsExactly("Bitcoin rose 2% today.", "Ether fell 4%.", "Only a title 5%");
    }

    @Test
    void NSF11_aSourceWithoutABodySelector_isNotFetched() {
        NewsSource plain = NewsSource.create(
                new NewsSourceSettings("Plain", "https://p.com/feed", 15, true, null, null, null, null), null);
        when(sources.findById(plain.getId())).thenReturn(Optional.of(plain));
        NewsArticle article = article(plain.getId(), "Title", "The excerpt.");
        NewsArticle orphan = article(UUID.randomUUID(), "Orphan", null);
        when(articles.findUnsummarized(any(), anyInt(), anyInt())).thenReturn(List.of(article, orphan));
        when(ai.summarize(any())).thenReturn(answer("Tóm tắt.", "NEUTRAL"));

        service(properties(10)).summarizeDue();

        verifyNoInteractions(rss);
        assertThat(sent(2)).extracting(NewsSummaryRequest::text).containsExactly("The excerpt.", "Orphan");
        assertThat(sent(2).get(1).source()).isNull();
    }

    @Test
    void NSF11_aRefusedAnswer_isAskedForOnceMoreWithTheReason() {
        NewsArticle article = article("Bitcoin", "x");
        when(articles.findUnsummarized(any(), anyInt(), anyInt())).thenReturn(List.of(article));
        when(ai.summarize(any()))
                .thenReturn(answer("Bitcoin chạm $90,000.", "BULLISH"))
                .thenReturn(answer("Bitcoin quanh $81,700.", "NEUTRAL"));

        assertThat(service(properties(30)).summarizeDue().summarized()).isEqualTo(1);

        List<NewsSummaryRequest> requests = sent(2);
        assertThat(requests.get(0).feedback()).isNull();
        assertThat(requests.get(1).feedback()).isEqualTo("these figures are not in the article: 90,000");
        verify(articles).saveSummary(article.getId(), "Tiêu đề.", "Bitcoin quanh $81,700.", "NEUTRAL", NOW);
    }

    @Test
    void NSF11_twoRefusedAnswers_leaveTheArticleForTheNextRun() {
        NewsArticle article = article("Bitcoin", "x");
        when(articles.findUnsummarized(any(), anyInt(), anyInt())).thenReturn(List.of(article));
        when(ai.summarize(any())).thenReturn(answer("Nhà đầu tư nên mua Bitcoin.", "BULLISH"));

        assertThat(service(properties(30)).summarizeDue()).isEqualTo(new SummaryRun(1, 0, 1));
        verify(ai, times(2)).summarize(any());
        verify(articles, never()).saveSummary(any(), any(), any(), any(), any());
        verify(articles)
                .recordFailedAttempt(eq(article.getId()), startsWith("the summary tells the reader to trade"), eq(NOW));
    }

    @Test
    void NSF11_anAnswerTooLongTwice_isCut_butAnUnknownSentimentIsNot() {
        NewsArticle article = article("Bitcoin", "x");
        when(articles.findUnsummarized(any(), anyInt(), anyInt())).thenReturn(List.of(article));
        String fortyWords = "thị trường ".repeat(20).strip();
        when(ai.summarize(any())).thenReturn(answer(fortyWords, "BEARISH"));

        service(properties(30)).summarizeDue();

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(articles).saveSummary(eq(article.getId()), eq("Tiêu đề."), stored.capture(), eq("BEARISH"), eq(NOW));
        assertThat(stored.getValue()).endsWith("…").hasSizeLessThan(fortyWords.length());

        NewsArticle other = article("Ether", "y");
        when(articles.findUnsummarized(any(), anyInt(), anyInt())).thenReturn(List.of(other));
        when(ai.summarize(any())).thenReturn(answer(fortyWords, "MIXED"));
        assertThat(service(properties(30)).summarizeDue().failed()).isEqualTo(1);
        verify(articles, never()).saveSummary(eq(other.getId()), any(), any(), any(), any());
    }

    @Test
    void D118_theTranslatedHeadline_isStored_andARefusedOneIsAskedForAgain() {
        NewsArticle article = article("Bitcoin ETFs see $635M outflows", "ETF outflows reached $635 million.");
        when(articles.findUnsummarized(any(), anyInt(), anyInt())).thenReturn(List.of(article));
        when(ai.summarize(any()))
                .thenReturn(answer("ETF Bitcoin bị rút $900 triệu", "ETF rút $635 triệu.", "BEARISH"))
                .thenReturn(answer("  ETF Bitcoin bị rút $635 triệu  ", "ETF rút $635 triệu.", "BEARISH"));

        assertThat(service(properties(30)).summarizeDue().summarized()).isEqualTo(1);

        assertThat(sent(2).get(1).feedback()).isEqualTo("these figures of the title are not in the article: 900");
        verify(articles)
                .saveSummary(article.getId(), "ETF Bitcoin bị rút $635 triệu", "ETF rút $635 triệu.", "BEARISH", NOW);
    }

    @Test
    void D118_aSummaryTooLong_isNotCut_whenItsHeadlineIsRefused() {
        NewsArticle article = article("Bitcoin", "x");
        when(articles.findUnsummarized(any(), anyInt(), anyInt())).thenReturn(List.of(article));
        String fortyWords = "thị trường ".repeat(20).strip();
        when(ai.summarize(any())).thenReturn(answer("Hãy mua Bitcoin", fortyWords, "BULLISH"));

        assertThat(service(properties(30)).summarizeDue()).isEqualTo(new SummaryRun(1, 0, 1));
        verify(articles, never()).saveSummary(any(), any(), any(), any(), any());
    }

    @Test
    void NSF11_anUnknownSentiment_isNamedToTheModel() {
        NewsArticle article = article("Bitcoin", "x");
        when(articles.findUnsummarized(any(), anyInt(), anyInt())).thenReturn(List.of(article));
        when(ai.summarize(any())).thenReturn(answer("Tóm tắt.", "MIXED")).thenReturn(answer("Tóm tắt.", "NEUTRAL"));

        service(properties(30)).summarizeDue();

        assertThat(sent(2).get(1).feedback()).startsWith("the sentiment \"MIXED\" is not one of [BULLISH");
    }

    @Test
    void NSF11_aRateLimitOrAServiceThatIsDown_endsTheRun_butOneBadArticleDoesNot() {
        NewsArticle first = article("One", "a");
        NewsArticle second = article("Two", "b");
        NewsArticle third = article("Three", "c");
        when(articles.findUnsummarized(any(), anyInt(), anyInt())).thenReturn(List.of(first, second, third));
        when(ai.summarize(any()))
                .thenThrow(new AiServiceException("LLM_REJECTED", false, "content blocked"))
                .thenThrow(new AiServiceException(AiServiceException.RATE_LIMITED, true, "slow down"))
                .thenReturn(answer("Tóm tắt.", "NEUTRAL"));

        assertThat(service(properties(30)).summarizeDue()).isEqualTo(new SummaryRun(3, 0, 2));
        verify(ai, times(2)).summarize(any());
        verify(articles).recordFailedAttempt(first.getId(), "LLM_REJECTED: content blocked", NOW);
        verify(articles, never()).recordFailedAttempt(eq(second.getId()), any(), any());
    }

    @Test
    void D119_aFailureIsRecordedWithinTheLengthOfItsColumn() {
        NewsArticle article = article("Bitcoin", "x");
        when(articles.findUnsummarized(any(), anyInt(), anyInt())).thenReturn(List.of(article));
        when(ai.summarize(any())).thenThrow(new AiServiceException("LLM_REJECTED", false, "x".repeat(900)));

        service(properties(30)).summarizeDue();

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(articles).recordFailedAttempt(eq(article.getId()), error.capture(), eq(NOW));
        assertThat(error.getValue())
                .hasSize(NewsSummaryServiceImpl.MAX_ERROR_LENGTH)
                .startsWith("LLM_REJECTED: x");
    }

    @Test
    void D119_summarizeNow_replacesTheSummary_whateverItsAgeAndAttempts() {
        NewsArticle article = article("Bitcoin holds $81K", "Excerpt only.");
        when(articles.findById(article.getId())).thenReturn(Optional.of(article));
        when(ai.summarize(any())).thenReturn(answer("Bitcoin quanh $81,700.", "NEUTRAL"));

        assertThat(service(properties(30)).summarizeNow(article.getId())).isEmpty();

        verify(articles).replaceSummary(article.getId(), "Tiêu đề.", "Bitcoin quanh $81,700.", "NEUTRAL", NOW);
        verify(articles, never()).saveSummary(any(), any(), any(), any(), any());
        verify(articles, never()).findUnsummarized(any(), anyInt(), anyInt());
    }

    @Test
    void D119_summarizeNow_refusedOrRejected_isRecordedAndAnswered() {
        NewsArticle article = article("Bitcoin", "x");
        when(articles.findById(article.getId())).thenReturn(Optional.of(article));
        when(ai.summarize(any())).thenReturn(answer("Hãy mua Bitcoin.", "BULLISH"));

        assertThat(service(properties(30)).summarizeNow(article.getId()))
                .hasValueSatisfying(reason -> assertThat(reason).startsWith("the summary tells the reader to trade"));

        when(ai.summarize(any())).thenThrow(new AiServiceException("LLM_REJECTED", false, "blocked"));
        assertThat(service(properties(30)).summarizeNow(article.getId())).contains("LLM_REJECTED: blocked");
        verify(articles, times(2)).recordFailedAttempt(eq(article.getId()), any(), eq(NOW));
        verify(articles, never()).replaceSummary(any(), any(), any(), any(), any());
    }

    @Test
    void D119_summarizeNow_withTheServiceDown_isUnavailable_andCountsNoAttempt() {
        NewsArticle article = article("Bitcoin", "x");
        when(articles.findById(article.getId())).thenReturn(Optional.of(article));
        when(ai.summarize(any())).thenThrow(new AiServiceException(AiServiceException.UNREACHABLE, true, "down"));

        assertThatThrownBy(() -> service(properties(30)).summarizeNow(article.getId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode())
                        .isEqualTo(ErrorCode.NEWS_SUMMARY_UNAVAILABLE));
        verify(articles, never()).recordFailedAttempt(any(), any(), any());

        UUID unknown = UUID.randomUUID();
        when(articles.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(properties(30)).summarizeNow(unknown))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void NSF11_thePrompt_isReadOnce_andAMissingOneFailsTheRun() {
        NewsArticle article = article("Bitcoin", "x");
        when(articles.findUnsummarized(any(), anyInt(), anyInt())).thenReturn(List.of(article));
        when(ai.summarize(any())).thenReturn(answer("Tóm tắt.", "NEUTRAL"));
        NewsSummaryServiceImpl service = service(properties(30));

        service.summarizeDue();
        service.summarizeDue();
        assertThat(sent(2))
                .extracting(NewsSummaryRequest::systemPrompt)
                .containsOnly("Write in Vietnamese, at most 30 words.");

        Resource missing = new ClassPathResource("prompts/does-not-exist.txt");
        assertThatThrownBy(() -> service(properties(30, 80, missing)).summarizeDue())
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("prompt cannot be read");
    }

    private NewsSummaryServiceImpl service(NewsSummaryProperties properties) {
        return new NewsSummaryServiceImpl(
                articles,
                sources,
                rss,
                ai,
                properties,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static NewsSummaryProperties properties(int maxWords) {
        return properties(maxWords, 5);
    }

    private static NewsSummaryProperties properties(int maxWords, int minBodyWords) {
        return properties(
                maxWords,
                minBodyWords,
                new ByteArrayResource(
                        "Write in {language}, at most {maxWords} words.".getBytes(StandardCharsets.UTF_8)));
    }

    private static NewsSummaryProperties properties(int maxWords, int minBodyWords, Resource prompt) {
        return new NewsSummaryProperties(
                true,
                Duration.ofMinutes(5),
                Duration.ofMinutes(2),
                10,
                Duration.ofDays(7),
                3,
                "test-model",
                0.2,
                512,
                "Vietnamese",
                maxWords,
                20,
                1200,
                minBodyWords,
                2000,
                prompt);
    }

    private List<NewsSummaryRequest> sent(int times) {
        ArgumentCaptor<NewsSummaryRequest> requests = ArgumentCaptor.forClass(NewsSummaryRequest.class);
        verify(ai, times(times)).summarize(requests.capture());
        return requests.getAllValues();
    }

    private static NewsSummaryResult answerWithBody(String body) {
        return new NewsSummaryResult("Tiêu đề.", "Bitcoin đi ngang.", "NEUTRAL", "test-model", 100, 20, body);
    }

    /** The article page of {@link #BODY} with a preview image. */
    private static WebPage page(String ogImage) {
        String html = BODY.replace(
                "<html><body>", "<html><head><meta property=\"og:image\" content=\"" + ogImage + "\"></head><body>");
        return new WebPage("https://e.com/a", html.getBytes(StandardCharsets.UTF_8), "text/html");
    }

    private static NewsSummaryResult answer(String summary, String sentiment) {
        return answer("Tiêu đề.", summary, sentiment);
    }

    private static NewsSummaryResult answer(String title, String summary, String sentiment) {
        return new NewsSummaryResult(title, summary, sentiment, "test-model", 100, 20);
    }

    private NewsArticle article(String title, String excerpt) {
        return article(source.getId(), title, excerpt);
    }

    /** An article as the repository would return it; the entity has no public constructor, as it is read only. */
    private static NewsArticle article(UUID sourceId, String title, String excerpt) {
        try {
            var constructor = NewsArticle.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            NewsArticle article = constructor.newInstance();
            set(article, "sourceId", sourceId);
            set(article, "title", title);
            set(article, "originalUrl", "https://e.com/" + title.hashCode());
            set(article, "excerpt", excerpt);
            set(article, "publishedAt", NOW);
            set(article, "collectedAt", NOW);
            return article;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
