package com.cryptopilot.news.service.impl;

import com.cryptopilot.ai.AiServiceException;
import com.cryptopilot.ai.NewsSummaryApi;
import com.cryptopilot.ai.NewsSummaryRequest;
import com.cryptopilot.ai.NewsSummaryResult;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.news.calculator.ArticleImage;
import com.cryptopilot.news.calculator.ArticleText;
import com.cryptopilot.news.calculator.SummaryCheck;
import com.cryptopilot.news.client.RssClient;
import com.cryptopilot.news.client.RssClientException;
import com.cryptopilot.news.client.WebPage;
import com.cryptopilot.news.config.NewsSummaryProperties;
import com.cryptopilot.news.entity.NewsArticle;
import com.cryptopilot.news.entity.NewsSource;
import com.cryptopilot.news.model.SummaryRun;
import com.cryptopilot.news.model.enums.SentimentLabel;
import com.cryptopilot.news.repository.NewsArticleRepository;
import com.cryptopilot.news.repository.NewsSourceRepository;
import com.cryptopilot.news.service.NewsSummaryService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NSF-11, article by article and outside any transaction: reading a page and waiting for the model take seconds, and
 * no database connection waits on them. Only the single statement that stores the summary runs in one.
 *
 * <p>The model reads the article itself where the source names a body selector: the page is read again and its text
 * extracted, then dropped (BR-48). Where that fails — no selector, a paywall, a page too short to be the article — the
 * headline and the feed's excerpt are summarized instead, so every article can be summarized.
 *
 * <p>The model also translates the headline (D-127), so a Trader reads the feed in one language. An answer is stored
 * only once its headline and its summary pass {@link SummaryCheck}: within the word limits, no advice to trade, no
 * figure the article does not hold. A refused answer is asked for once more with the reason; an answer whose only fault is
 * its length is cut rather than thrown away. Otherwise the article keeps no summary and the next run takes it up again
 * (NSF-11), up to the configured number of attempts, after which it waits for an administrator (D-128). A provider
 * that rate limits, a service that is down or not configured ends the run without counting an attempt: the other
 * articles would fail the same way, and each would cost a request.
 *
 * <p>The same answer carries the article written again in the app's words, shorter than the original, for its page in
 * the app (D-129). It is checked like the summary but never cut; one that still fails after the second request is
 * dropped and the summary is stored alone. The page read for the text also gives the cover image where the feed gave
 * none, by its address only.
 *
 * <p>Rule: NSF-11; BR-48; D-127; D-129; the hard scope boundary (no investment advice).
 */
@Service
public class NewsSummaryServiceImpl implements NewsSummaryService {

    private static final Logger log = LoggerFactory.getLogger(NewsSummaryServiceImpl.class);

    /** The length of {@code news_article.summary_error}. */
    static final int MAX_ERROR_LENGTH = 500;

    private final NewsArticleRepository articles;
    private final NewsSourceRepository sources;
    private final RssClient rss;
    private final NewsSummaryApi ai;
    private final NewsSummaryProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private volatile String systemPrompt;

    public NewsSummaryServiceImpl(
            NewsArticleRepository articles,
            NewsSourceRepository sources,
            RssClient rss,
            NewsSummaryApi ai,
            NewsSummaryProperties properties,
            TransactionTemplate transactions,
            Clock clock) {
        this.articles = articles;
        this.sources = sources;
        this.rss = rss;
        this.ai = ai;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public SummaryRun summarizeDue() {
        List<NewsArticle> due = articles.findUnsummarized(
                clock.instant().minus(properties.maxAge()), properties.maxAttempts(), properties.maxArticlesPerRun());
        if (due.isEmpty()) {
            return SummaryRun.NOTHING_DUE;
        }
        String prompt = systemPrompt();
        Map<UUID, Optional<NewsSource>> sourceById = new HashMap<>();
        int summarized = 0;
        int failed = 0;
        for (NewsArticle article : due) {
            NewsSource source = sourceById
                    .computeIfAbsent(article.getSourceId(), sources::findById)
                    .orElse(null);
            try {
                Optional<String> refused = summarize(article, source, prompt, false);
                if (refused.isEmpty()) {
                    summarized++;
                } else {
                    failed++;
                    recordFailure(article, refused.get());
                }
            } catch (AiServiceException e) {
                failed++;
                if (e.stopsTheRun()) {
                    // Not the article's fault: the attempt is not counted against it.
                    log.warn("NSF-11 stops this run: {} ({}); the next run tries again", e.getMessage(), e.code());
                    break;
                }
                log.warn("NSF-11 article {} not summarized: {} ({})", article.getId(), e.getMessage(), e.code());
                recordFailure(article, e.code() + ": " + e.getMessage());
            }
        }
        return new SummaryRun(due.size(), summarized, failed);
    }

    @Override
    public Optional<String> summarizeNow(UUID articleId) {
        NewsArticle article = articles.findById(articleId)
                .orElseThrow(() -> new ResourceNotFoundException("News article", articleId));
        NewsSource source = sources.findById(article.getSourceId()).orElse(null);
        try {
            Optional<String> refused = summarize(article, source, systemPrompt(), true);
            refused.ifPresent(reason -> recordFailure(article, reason));
            return refused;
        } catch (AiServiceException e) {
            if (e.stopsTheRun()) {
                throw new BusinessException(
                        ErrorCode.NEWS_SUMMARY_UNAVAILABLE,
                        "the AI Service cannot summarize now: " + e.getMessage() + " (" + e.code() + ")");
            }
            String reason = e.code() + ": " + e.getMessage();
            recordFailure(article, reason);
            return Optional.of(reason);
        }
    }

    private void recordFailure(NewsArticle article, String reason) {
        String error = reason.length() <= MAX_ERROR_LENGTH ? reason : reason.substring(0, MAX_ERROR_LENGTH);
        transactions.executeWithoutResult(
                status -> articles.recordFailedAttempt(article.getId(), error, clock.instant()));
    }

    /**
     * Asks the model for the article and stores a sound answer: over the summary it has when {@code replace}, else
     * only where it has none. Why the answer was refused, or empty when it was stored.
     */
    private Optional<String> summarize(NewsArticle article, NewsSource source, String prompt, boolean replace) {
        Material read = material(article, source);
        String text = read.text();
        String material = article.getTitle() + "\n\n" + text;
        NewsSummaryRequest request = new NewsSummaryRequest(
                properties.model(),
                prompt,
                properties.temperature(),
                properties.maxOutputTokens(),
                article.getTitle(),
                source == null ? null : source.getSourceName(),
                text,
                null);
        NewsSummaryResult answer = ai.summarize(request);
        NewsSummaryResult first = answer;
        Optional<String> problem = problem(first, material).or(() -> articleProblem(first, material));
        if (problem.isPresent()) {
            log.info("NSF-11 article {}: asking again because {}", article.getId(), problem.get());
            answer = ai.summarize(request.withFeedback(problem.get()));
        }
        String summary = answer.summary().strip();
        Optional<String> remaining = problem(answer, material);
        if (remaining.isPresent()) {
            if (sentiment(answer).isPresent()
                    && titleProblem(answer, material).isEmpty()
                    && SummaryCheck.onlyTooLong(summary, material, properties.maxWords())) {
                summary = SummaryCheck.cut(summary, properties.maxWords());
            } else {
                log.info("NSF-11 article {} keeps no summary: {}", article.getId(), remaining.get());
                return remaining;
            }
        }
        SentimentLabel sentiment = sentiment(answer).orElseThrow();
        String title = answer.title().strip();
        String stored = summary;
        String body = body(article, answer, material);
        String image = read.imageUrl();
        Instant now = clock.instant();
        // Without replace, no row written means another run stored a summary meanwhile, which is as good as this one.
        transactions.executeWithoutResult(status -> {
            int written = replace
                    ? articles.replaceSummary(article.getId(), title, stored, sentiment.name(), now)
                    : articles.saveSummary(article.getId(), title, stored, sentiment.name(), now);
            if (written > 0) {
                articles.saveArticle(article.getId(), body, image);
            }
        });
        return Optional.empty();
    }

    private Optional<String> articleProblem(NewsSummaryResult answer, String material) {
        return SummaryCheck.articleProblem(answer.body(), material, properties.maxArticleWords());
    }

    /** The article written again as it is stored: stripped, or {@code null} when there is none or it fails its check. */
    private String body(NewsArticle article, NewsSummaryResult answer, String material) {
        if (answer.body() == null || answer.body().isBlank()) {
            return null;
        }
        Optional<String> problem = articleProblem(answer, material);
        if (problem.isPresent()) {
            log.info("NSF-11 article {} keeps its summary without a body: {}", article.getId(), problem.get());
            return null;
        }
        return answer.body().strip();
    }

    /** Why an answer cannot be stored, or empty when it can. */
    private Optional<String> problem(NewsSummaryResult answer, String material) {
        if (sentiment(answer).isEmpty()) {
            return Optional.of("the sentiment \"" + answer.sentiment() + "\" is not one of "
                    + Arrays.toString(SentimentLabel.values()));
        }
        return titleProblem(answer, material)
                .or(() -> SummaryCheck.problem(answer.summary(), material, properties.maxWords()));
    }

    private Optional<String> titleProblem(NewsSummaryResult answer, String material) {
        return SummaryCheck.titleProblem(answer.title(), material, properties.maxTitleWords());
    }

    private static Optional<SentimentLabel> sentiment(NewsSummaryResult answer) {
        return Arrays.stream(SentimentLabel.values())
                .filter(label -> label.name().equals(answer.sentiment()))
                .findFirst();
    }

    /**
     * What the model reads, the article's own body when the source names where it is and the page gives it, else the
     * feed's excerpt, neither of them stored; and the page's cover image when the article has none yet (D-129).
     */
    private Material material(NewsArticle article, NewsSource source) {
        String excerpt = article.getExcerpt() == null ? "" : article.getExcerpt();
        String fallback = excerpt.isBlank() ? article.getTitle() : excerpt;
        if (source == null || source.getBodySelector() == null) {
            return new Material(fallback, null);
        }
        String image = null;
        try {
            WebPage page = rss.readPage(article.getOriginalUrl(), source.getUserAgent());
            if (article.getImageUrl() == null) {
                image = ArticleImage.fromPage(page.body(), page.url()).orElse(null);
            }
            Optional<String> body = ArticleText.extract(
                    page.body(),
                    page.url(),
                    source.getBodySelector(),
                    source.getExcludedSelectors(),
                    properties.maxInputWords());
            if (body.isPresent() && ArticleText.words(body.get()) >= properties.minBodyWords()) {
                return new Material(body.get(), image);
            }
            log.debug(
                    "NSF-11 article {}: the page has no body under the selectors; the excerpt is used",
                    article.getId());
        } catch (RssClientException e) {
            log.debug(
                    "NSF-11 article {}: the page could not be read ({}); the excerpt is used",
                    article.getId(),
                    e.getMessage());
        }
        return new Material(fallback, image);
    }

    /**
     * @param text what the model reads
     * @param imageUrl the page's cover image, or {@code null} when the article has one or the page names none
     */
    private record Material(String text, String imageUrl) {}

    /** The instructions with the configured language and length, read once from the configured resource. */
    private String systemPrompt() {
        String prompt = systemPrompt;
        if (prompt == null) {
            try {
                prompt = properties
                        .prompt()
                        .getContentAsString(StandardCharsets.UTF_8)
                        .replace("{language}", properties.language())
                        .replace("{maxWords}", String.valueOf(properties.maxWords()))
                        .replace("{maxTitleWords}", String.valueOf(properties.maxTitleWords()))
                        .replace("{maxArticleWords}", String.valueOf(properties.maxArticleWords()));
            } catch (IOException e) {
                throw new UncheckedIOException("the news summary prompt cannot be read: " + properties.prompt(), e);
            }
            systemPrompt = prompt;
        }
        return prompt;
    }
}
