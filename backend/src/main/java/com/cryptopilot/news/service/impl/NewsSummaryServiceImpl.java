package com.cryptopilot.news.service.impl;

import com.cryptopilot.ai.AiServiceException;
import com.cryptopilot.ai.NewsSummaryApi;
import com.cryptopilot.ai.NewsSummaryRequest;
import com.cryptopilot.ai.NewsSummaryResult;
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
 * <p>An answer is stored only once it passes {@link SummaryCheck}: within the word limit, no advice to trade, no figure
 * the article does not hold. A refused answer is asked for once more with the reason; an answer whose only fault is
 * its length is cut rather than thrown away. Otherwise the article keeps no summary and the next run takes it up again
 * (NSF-11). A provider that rate limits, a service that is down or not configured ends the run: the other articles
 * would fail the same way, and each would cost a request.
 *
 * <p>Rule: NSF-11; BR-48; the hard scope boundary (no investment advice).
 */
@Service
public class NewsSummaryServiceImpl implements NewsSummaryService {

    private static final Logger log = LoggerFactory.getLogger(NewsSummaryServiceImpl.class);

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
        List<NewsArticle> due =
                articles.findUnsummarized(clock.instant().minus(properties.maxAge()), properties.maxArticlesPerRun());
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
                if (summarize(article, source, prompt)) {
                    summarized++;
                } else {
                    failed++;
                }
            } catch (AiServiceException e) {
                failed++;
                if (e.stopsTheRun()) {
                    log.warn("NSF-11 stops this run: {} ({}); the next run tries again", e.getMessage(), e.code());
                    break;
                }
                log.warn("NSF-11 article {} not summarized: {} ({})", article.getId(), e.getMessage(), e.code());
            }
        }
        return new SummaryRun(due.size(), summarized, failed);
    }

    /** Whether the article now has a summary. */
    private boolean summarize(NewsArticle article, NewsSource source, String prompt) {
        String text = articleText(article, source);
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
        Optional<String> problem = problem(answer, material);
        if (problem.isPresent()) {
            log.info("NSF-11 article {}: asking again because {}", article.getId(), problem.get());
            answer = ai.summarize(request.withFeedback(problem.get()));
        }
        String summary = answer.summary().strip();
        Optional<String> remaining = problem(answer, material);
        if (remaining.isPresent()) {
            if (sentiment(answer).isPresent() && SummaryCheck.onlyTooLong(summary, material, properties.maxWords())) {
                summary = SummaryCheck.cut(summary, properties.maxWords());
            } else {
                log.info("NSF-11 article {} keeps no summary: {}", article.getId(), remaining.get());
                return false;
            }
        }
        SentimentLabel sentiment = sentiment(answer).orElseThrow();
        String stored = summary;
        // No row written means another run stored a summary meanwhile, which is as good as this one.
        transactions.executeWithoutResult(
                status -> articles.saveSummary(article.getId(), stored, sentiment.name(), clock.instant()));
        return true;
    }

    /** Why an answer cannot be stored, or empty when it can. */
    private Optional<String> problem(NewsSummaryResult answer, String material) {
        if (sentiment(answer).isEmpty()) {
            return Optional.of("the sentiment \"" + answer.sentiment() + "\" is not one of "
                    + Arrays.toString(SentimentLabel.values()));
        }
        return SummaryCheck.problem(answer.summary(), material, properties.maxWords());
    }

    private static Optional<SentimentLabel> sentiment(NewsSummaryResult answer) {
        return Arrays.stream(SentimentLabel.values())
                .filter(label -> label.name().equals(answer.sentiment()))
                .findFirst();
    }

    /**
     * The text the model reads: the article's own body when the source names where it is and the page gives it, else
     * the feed's excerpt. Neither is stored.
     */
    private String articleText(NewsArticle article, NewsSource source) {
        String excerpt = article.getExcerpt() == null ? "" : article.getExcerpt();
        if (source == null || source.getBodySelector() == null) {
            return excerpt.isBlank() ? article.getTitle() : excerpt;
        }
        try {
            WebPage page = rss.readPage(article.getOriginalUrl(), source.getUserAgent());
            Optional<String> body = ArticleText.extract(
                    page.body(),
                    page.url(),
                    source.getBodySelector(),
                    source.getExcludedSelectors(),
                    properties.maxInputWords());
            if (body.isPresent() && ArticleText.words(body.get()) >= properties.minBodyWords()) {
                return body.get();
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
        return excerpt.isBlank() ? article.getTitle() : excerpt;
    }

    /** The instructions with the configured language and length, read once from the configured resource. */
    private String systemPrompt() {
        String prompt = systemPrompt;
        if (prompt == null) {
            try {
                prompt = properties
                        .prompt()
                        .getContentAsString(StandardCharsets.UTF_8)
                        .replace("{language}", properties.language())
                        .replace("{maxWords}", String.valueOf(properties.maxWords()));
            } catch (IOException e) {
                throw new UncheckedIOException("the news summary prompt cannot be read: " + properties.prompt(), e);
            }
            systemPrompt = prompt;
        }
        return prompt;
    }
}
