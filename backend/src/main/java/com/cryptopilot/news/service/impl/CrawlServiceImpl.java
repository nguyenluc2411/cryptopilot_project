package com.cryptopilot.news.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.news.calculator.ArticleCandidates;
import com.cryptopilot.news.calculator.CoinTagger;
import com.cryptopilot.news.client.RssClient;
import com.cryptopilot.news.client.RssClientException;
import com.cryptopilot.news.client.RssFeed;
import com.cryptopilot.news.client.RssItem;
import com.cryptopilot.news.config.NewsCrawlProperties;
import com.cryptopilot.news.entity.NewsCrawlLog;
import com.cryptopilot.news.entity.NewsSource;
import com.cryptopilot.news.event.NewsSourceFailing;
import com.cryptopilot.news.model.ArticleCandidate;
import com.cryptopilot.news.model.CoinTag;
import com.cryptopilot.news.model.CrawlRun;
import com.cryptopilot.news.model.TaggableCoin;
import com.cryptopilot.news.model.enums.CrawlStatus;
import com.cryptopilot.news.repository.NewsArticleRepository;
import com.cryptopilot.news.repository.NewsCrawlLogRepository;
import com.cryptopilot.news.repository.NewsSourceRepository;
import com.cryptopilot.news.service.CrawlService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NSF-10, source by source. The feed is read outside any transaction — it may take the 30 seconds of NSF-10 — and what
 * was read is then stored in one transaction per source together with the source's crawl time and its crawl log, so a
 * source is either crawled with its log or not at all.
 *
 * <p>Deduplication (BR-48): an item whose normalized address was stored before is left out by the unique key on the
 * address hash, and one whose normalized title was published within the window of an article already stored is the
 * same story told twice and is left out too. Items of one feed are stored in feed order, so a title repeated within
 * the feed is stored once.
 *
 * <p>A feed that cannot be read is logged FAILED and its source waits one interval like any other, so a source that is
 * down is not asked every five minutes. When the failures in a row reach the notice threshold the administrators are
 * told, once per run of failures ({@link NewsSourceFailing}, stored with the failure by the event publication
 * registry).
 *
 * <p>The coins are read once per run and compiled into one {@link CoinTagger} (TECHNICAL_DESIGN 7.13).
 *
 * <p>An administrator can crawl one source at once (T-080b): the same crawl, refused within a short cooldown of the
 * source's last crawl so a repeated click does not read the outlet twice in a row.
 *
 * <p>Rule: NSF-10; BR-48, BR-49.
 */
@Service
@RequiredArgsConstructor
public class CrawlServiceImpl implements CrawlService {

    private static final Logger log = LoggerFactory.getLogger(CrawlServiceImpl.class);

    private final NewsSourceRepository sources;
    private final NewsCrawlLogRepository crawlLogs;
    private final NewsArticleRepository articles;
    private final RssClient rss;
    private final MarketApi market;
    private final NewsCrawlProperties properties;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Override
    public CrawlRun crawlDue() {
        List<NewsSource> due = sources.findDue(clock.instant());
        if (due.isEmpty()) {
            return CrawlRun.NOTHING_DUE;
        }
        CoinTagger tagger = CoinTagger.of(taggableCoins());
        int failed = 0;
        int inserted = 0;
        for (NewsSource source : due) {
            try {
                Optional<Integer> stored = crawl(source, tagger);
                if (stored.isEmpty()) {
                    failed++;
                } else {
                    inserted += stored.get();
                }
            } catch (RuntimeException defect) {
                // Not a feed that failed but a fault here: nothing of the source was stored, and the next run retries.
                failed++;
                log.error("NSF-10 crawl of news source {} stopped by an error", source.getId(), defect);
            }
        }
        return new CrawlRun(due.size(), failed, inserted);
    }

    @Override
    public NewsCrawlLog crawlNow(UUID sourceId) {
        NewsSource source =
                sources.findById(sourceId).orElseThrow(() -> new ResourceNotFoundException("News source", sourceId));
        Instant now = clock.instant();
        if (source.getLastCrawledAt() != null) {
            Instant allowed = source.getLastCrawledAt().plus(properties.manualCrawlCooldown());
            if (now.isBefore(allowed)) {
                long seconds = Math.max(1, Duration.between(now, allowed).toSeconds());
                throw new BusinessException(
                        ErrorCode.NEWS_SOURCE_CRAWLED_RECENTLY,
                        "news source " + sourceId + " was crawled at " + source.getLastCrawledAt(),
                        seconds);
            }
        }
        crawl(source, CoinTagger.of(taggableCoins()));
        return crawlLogs.findLatestOf(List.of(sourceId)).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("News source", sourceId));
    }

    /** The articles stored from the source, or empty when its feed could not be read. */
    private Optional<Integer> crawl(NewsSource source, CoinTagger tagger) {
        Instant started = clock.instant();
        RssFeed feed;
        try {
            feed = rss.read(source.getFeedUrl(), source.getUserAgent());
        } catch (RssClientException e) {
            log.warn(
                    "NSF-10 news source {} ({}) could not be read: {}",
                    source.getId(),
                    source.getSourceName(),
                    e.getMessage());
            transactions.executeWithoutResult(status -> recordFailure(source, started, e));
            return Optional.empty();
        }
        List<ArticleCandidate> candidates = new ArrayList<>();
        int unusable = 0;
        for (RssItem item : feed.items()) {
            if (!ArticleCandidates.passes(source.getCategoryFilter(), item.categories())) {
                continue;
            }
            Optional<ArticleCandidate> candidate = ArticleCandidates.from(
                    item.title(),
                    item.link(),
                    item.publishedAt(),
                    item.description(),
                    item.imageUrl(),
                    started,
                    tagger);
            if (candidate.isPresent()) {
                candidates.add(candidate.get());
            } else {
                unusable++;
            }
        }
        int found = feed.items().size();
        String error = unusable == 0 ? null : unusable + " of " + found + " items had no headline or no web address";
        return Optional.ofNullable(transactions.execute(status -> {
            if (sources.markCrawled(source.getId(), started) == 0) {
                return 0;
            }
            int inserted = store(source.getId(), candidates, started);
            crawlLogs.save(NewsCrawlLog.record(
                    source.getId(),
                    started,
                    clock.instant(),
                    error == null ? CrawlStatus.SUCCESS : CrawlStatus.PARTIAL,
                    found,
                    inserted,
                    error));
            return inserted;
        }));
    }

    /** Stores the candidates that are new stories, with their coins; how many were stored. */
    private int store(UUID sourceId, List<ArticleCandidate> candidates, Instant collectedAt) {
        Duration window = properties.duplicateTitleWindow();
        int inserted = 0;
        for (ArticleCandidate candidate : candidates) {
            if (articles.existsTitleBetween(
                    candidate.titleHash(),
                    candidate.publishedAt().minus(window),
                    candidate.publishedAt().plus(window))) {
                continue;
            }
            UUID id = UuidV7.next();
            int written = articles.insert(
                    id,
                    sourceId,
                    candidate.title(),
                    candidate.originalUrl(),
                    candidate.urlHash(),
                    candidate.titleHash(),
                    candidate.excerpt(),
                    candidate.imageUrl(),
                    candidate.publishedAt(),
                    collectedAt);
            if (written == 0) {
                continue;
            }
            inserted++;
            for (CoinTag tag : candidate.coins()) {
                articles.tagCoin(id, tag.coinId(), tag.primary());
            }
        }
        return inserted;
    }

    /** Logs the failure, and tells the administrators when it completes the threshold of failures in a row. */
    private void recordFailure(NewsSource source, Instant started, RssClientException failure) {
        if (sources.markCrawled(source.getId(), started) == 0) {
            return;
        }
        NewsCrawlLog entry = crawlLogs.save(NewsCrawlLog.record(
                source.getId(),
                started,
                clock.instant(),
                CrawlStatus.FAILED,
                null,
                null,
                failure.reason() + ": " + failure.getMessage()));
        crawlLogs.flush();
        int threshold = properties.failureNoticeAfter();
        int inARow = 0;
        for (String status : crawlLogs.findLatestStatuses(source.getId(), threshold + 1)) {
            if (!CrawlStatus.FAILED.name().equals(status)) {
                break;
            }
            inARow++;
        }
        if (inARow == threshold) {
            events.publishEvent(new NewsSourceFailing(
                    source.getId(), source.getSourceName(), inARow, entry.getId(), entry.getErrorMessage(), started));
        }
    }

    /**
     * The listed coins with the names outlets write them as: the coin's own name when it is not just its symbol, and
     * the configured names of its symbol.
     */
    private List<TaggableCoin> taggableCoins() {
        List<TaggableCoin> coins = new ArrayList<>();
        for (CoinListing coin : market.listedCoins()) {
            List<String> names = new ArrayList<>();
            if (!coin.name().equalsIgnoreCase(coin.symbol())) {
                names.add(coin.name());
            }
            names.addAll(properties.namesOf(coin.symbol()));
            coins.add(new TaggableCoin(coin.coinId(), coin.symbol(), names));
        }
        return coins;
    }
}
