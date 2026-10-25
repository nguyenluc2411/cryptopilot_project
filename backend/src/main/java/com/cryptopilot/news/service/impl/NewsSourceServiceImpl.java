package com.cryptopilot.news.service.impl;

import com.cryptopilot.admin.AdminApi;
import com.cryptopilot.admin.AuditEntry;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.news.calculator.UrlNormalizer;
import com.cryptopilot.news.client.RssClient;
import com.cryptopilot.news.client.RssClientException;
import com.cryptopilot.news.dto.request.NewsSourceRequest;
import com.cryptopilot.news.dto.response.NewsCrawlLogResponse;
import com.cryptopilot.news.dto.response.NewsSourceResponse;
import com.cryptopilot.news.entity.NewsCrawlLog;
import com.cryptopilot.news.entity.NewsSource;
import com.cryptopilot.news.model.NewsSourceSettings;
import com.cryptopilot.news.repository.NewsCrawlLogRepository;
import com.cryptopilot.news.repository.NewsSourceRepository;
import com.cryptopilot.news.service.CrawlService;
import com.cryptopilot.news.service.NewsSourceService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jsoup.select.QueryParser;
import org.jsoup.select.Selector;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SCR-41. A source is saved only once its feed has been read (UC-49), and that read happens outside any transaction:
 * it may take the 30 seconds of NSF-10, and no database connection waits on another server for that long. The
 * uniqueness of the feed address is checked before the read, so a duplicate fails at once, and again in the
 * transaction that saves, where the unique key of the table has the last word.
 *
 * <p>Feed addresses are compared in their normalized form (TECHNICAL_DESIGN 7.13), so {@code HTTPS://Outlet.com/feed}
 * and {@code https://outlet.com/feed?utm_source=x} are one source. Selectors are parsed with the parser the crawl
 * reads pages with, so a selector the crawl could not use is refused when it is typed, not at the next crawl.
 *
 * <p>Rule: UC-49; BR-49, BR-57; NSF-10.
 */
@Service
@RequiredArgsConstructor
public class NewsSourceServiceImpl implements NewsSourceService {

    /** The unique key on {@code news_source.feed_url}. */
    static final String FEED_URL_KEY = "uq_news_source_feed_url";

    private static final int MAX_FEED_URL_LENGTH = 1000;

    private final NewsSourceRepository sources;
    private final NewsCrawlLogRepository crawlLogs;
    private final RssClient rss;
    private final AdminApi admin;
    private final CrawlService crawls;
    private final TransactionTemplate transactions;

    @Override
    @Transactional(readOnly = true)
    public List<NewsSourceResponse> list() {
        List<NewsSource> all = sources.findAllForConsole();
        if (all.isEmpty()) {
            return List.of();
        }
        Map<UUID, NewsCrawlLog> latest = crawlLogs
                .findLatestOf(all.stream().map(NewsSource::getId).toList())
                .stream()
                .collect(Collectors.toMap(NewsCrawlLog::getSourceId, Function.identity()));
        return all.stream()
                .map(source -> response(source, latest.get(source.getId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public NewsSourceResponse get(UUID sourceId) {
        NewsSource source = source(sourceId);
        return response(source, latestOf(source));
    }

    @Override
    public NewsSourceResponse create(UUID adminId, NewsSourceRequest request) {
        NewsSourceSettings settings = settings(request);
        if (sources.existsByFeedUrl(settings.feedUrl())) {
            throw alreadyRegistered(settings.feedUrl());
        }
        requireReadable(settings);
        return saving(settings.feedUrl(), () -> {
            if (sources.existsByFeedUrl(settings.feedUrl())) {
                throw alreadyRegistered(settings.feedUrl());
            }
            NewsSource source = NewsSource.create(settings, adminId);
            sources.save(source);
            sources.flush();
            admin.audit(new AuditEntry(
                    adminId,
                    AuditAction.CONFIGURATION_CREATED,
                    AuditedEntity.NEWS_SOURCE,
                    source.getId(),
                    null,
                    values(settings)));
            return response(source, null);
        });
    }

    @Override
    public NewsSourceResponse update(UUID adminId, UUID sourceId, NewsSourceRequest request) {
        NewsSourceSettings settings = settings(request);
        NewsSourceSettings stored = source(sourceId).settings();
        boolean addressChanged = !settings.feedUrl().equals(stored.feedUrl());
        if (addressChanged && sources.existsByFeedUrlAndIdNot(settings.feedUrl(), sourceId)) {
            throw alreadyRegistered(settings.feedUrl());
        }
        if (addressChanged || !Objects.equals(settings.userAgent(), stored.userAgent())) {
            requireReadable(settings);
        }
        return saving(settings.feedUrl(), () -> {
            NewsSource source = source(sourceId);
            NewsSourceSettings before = source.settings();
            if (before.equals(settings)) {
                return response(source, latestOf(source));
            }
            if (!settings.feedUrl().equals(before.feedUrl())
                    && sources.existsByFeedUrlAndIdNot(settings.feedUrl(), sourceId)) {
                throw alreadyRegistered(settings.feedUrl());
            }
            source.update(settings, adminId);
            sources.save(source);
            sources.flush();
            Map<String, Object> old = values(before);
            Map<String, Object> now = values(settings);
            admin.audit(new AuditEntry(
                    adminId,
                    AuditAction.CONFIGURATION_CHANGED,
                    AuditedEntity.NEWS_SOURCE,
                    source.getId(),
                    changed(old, now),
                    changed(now, old)));
            return response(source, latestOf(source));
        });
    }

    @Override
    @Transactional
    public void delete(UUID adminId, UUID sourceId) {
        NewsSource source = source(sourceId);
        if (sources.hasArticles(sourceId)) {
            throw new BusinessException(
                    ErrorCode.NEWS_SOURCE_IN_USE,
                    "articles came from " + source.getSourceName() + "; switch the source off instead");
        }
        Map<String, Object> before = values(source.settings());
        sources.delete(source);
        admin.audit(new AuditEntry(
                adminId, AuditAction.CONFIGURATION_DELETED, AuditedEntity.NEWS_SOURCE, sourceId, before, null));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NewsCrawlLogResponse> crawlLogs(UUID sourceId, Integer page, Integer pageSize) {
        source(sourceId);
        return PageResponse.of(
                crawlLogs.findBySourceIdOrderByStartedAtDescIdDesc(sourceId, Paging.of(page, pageSize)),
                NewsSourceServiceImpl::logResponse);
    }

    @Override
    public NewsCrawlLogResponse crawlNow(UUID sourceId) {
        // Not in a transaction: the feed is read outside one and the crawl stores what it read in its own.
        return logResponse(crawls.crawlNow(sourceId));
    }

    /**
     * The request as settings: the name and the optional values stripped, blanks as none, the feed address normalized
     * and every selector parsed. Each rejected field is named, all at once.
     */
    static NewsSourceSettings settings(NewsSourceRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        String feedUrl = UrlNormalizer.normalize(request.feedUrl())
                .filter(normalized -> normalized.length() <= MAX_FEED_URL_LENGTH)
                .orElse(null);
        if (feedUrl == null) {
            errors.put("feedUrl", "MSG15");
        }
        String bodySelector = optional(request.bodySelector());
        if (!parses(bodySelector)) {
            errors.put("bodySelector", "MSG15");
        }
        String excludedSelectors = optional(request.excludedSelectors());
        if (!parses(excludedSelectors)) {
            errors.put("excludedSelectors", "MSG15");
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException("the news source settings break " + errors.keySet(), errors);
        }
        return new NewsSourceSettings(
                request.sourceName().strip(),
                feedUrl,
                request.crawlIntervalMinutes(),
                request.active(),
                optional(request.categoryFilter()),
                bodySelector,
                excludedSelectors,
                optional(request.userAgent()));
    }

    /** The test read of UC-49: the feed must answer with an RSS or Atom document, with the agent the source sends. */
    private void requireReadable(NewsSourceSettings settings) {
        try {
            rss.read(settings.feedUrl(), settings.userAgent());
        } catch (RssClientException e) {
            throw new FieldValidationException(
                    ErrorCode.NEWS_FEED_UNREADABLE,
                    "the feed could not be read (" + e.reason() + "): " + e.getMessage(),
                    Map.of("feedUrl", "MSG15"));
        }
    }

    /**
     * Runs {@code work} in one transaction; a concurrent save of the same address, which the checks before cannot see,
     * fails on the unique key and is reported as the duplicate it is.
     */
    private NewsSourceResponse saving(String feedUrl, Supplier<NewsSourceResponse> work) {
        try {
            return transactions.execute(status -> work.get());
        } catch (DataIntegrityViolationException e) {
            String cause = String.valueOf(e.getMostSpecificCause().getMessage());
            if (cause.contains(FEED_URL_KEY)) {
                throw alreadyRegistered(feedUrl);
            }
            throw e;
        }
    }

    private static boolean parses(String selector) {
        if (selector == null) {
            return true;
        }
        try {
            QueryParser.parse(selector);
            return true;
        } catch (Selector.SelectorParseException | IllegalArgumentException e) {
            return false;
        }
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private NewsSource source(UUID sourceId) {
        return sources.findById(sourceId).orElseThrow(() -> new ResourceNotFoundException("News source", sourceId));
    }

    private NewsCrawlLog latestOf(NewsSource source) {
        return crawlLogs.findLatestOf(List.of(source.getId())).stream()
                .findFirst()
                .orElse(null);
    }

    private static BusinessException alreadyRegistered(String feedUrl) {
        return new BusinessException(
                ErrorCode.NEWS_SOURCE_ALREADY_REGISTERED, feedUrl + " is already the feed of a news source");
    }

    private static NewsSourceResponse response(NewsSource source, NewsCrawlLog latest) {
        Instant nextCrawlAt = source.isActive() && source.getLastCrawledAt() != null
                ? source.getLastCrawledAt().plus(Duration.ofMinutes(source.getCrawlIntervalMinutes()))
                : null;
        return new NewsSourceResponse(
                source.getId(),
                source.getSourceName(),
                source.getFeedUrl(),
                source.getSourceType(),
                source.getCrawlIntervalMinutes(),
                source.isActive(),
                source.getCategoryFilter(),
                source.getBodySelector(),
                source.getExcludedSelectors(),
                source.getUserAgent(),
                source.getLastCrawledAt(),
                latest == null
                        ? null
                        : new NewsSourceResponse.LastCrawl(
                                latest.getStartedAt(),
                                latest.getStatus(),
                                latest.getArticlesFound(),
                                latest.getArticlesInserted()),
                nextCrawlAt,
                source.getCreatedAt(),
                source.getUpdatedAt());
    }

    private static NewsCrawlLogResponse logResponse(NewsCrawlLog log) {
        return new NewsCrawlLogResponse(
                log.getId(),
                log.getStartedAt(),
                log.getFinishedAt(),
                log.getStatus(),
                log.getArticlesFound(),
                log.getArticlesInserted(),
                log.getErrorMessage());
    }

    /** What an administrator sets on a source, for the audit entry. */
    private static Map<String, Object> values(NewsSourceSettings settings) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sourceName", settings.sourceName());
        values.put("feedUrl", settings.feedUrl());
        values.put("crawlIntervalMinutes", settings.crawlIntervalMinutes());
        values.put("active", settings.active());
        values.put("categoryFilter", settings.categoryFilter());
        values.put("bodySelector", settings.bodySelector());
        values.put("excludedSelectors", settings.excludedSelectors());
        values.put("userAgent", settings.userAgent());
        return values;
    }

    /** The entries of {@code values} that differ in {@code other}, the name always kept to name the source. */
    private static Map<String, Object> changed(Map<String, Object> values, Map<String, Object> other) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key.equals("sourceName") || !Objects.equals(value, other.get(key))) {
                result.put(key, value);
            }
        });
        return result;
    }
}
