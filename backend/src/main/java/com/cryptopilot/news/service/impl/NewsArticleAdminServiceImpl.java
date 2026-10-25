package com.cryptopilot.news.service.impl;

import com.cryptopilot.admin.AdminApi;
import com.cryptopilot.admin.AuditEntry;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.news.config.NewsSummaryProperties;
import com.cryptopilot.news.dto.response.AdminNewsArticleResponse;
import com.cryptopilot.news.model.ConsoleArticle;
import com.cryptopilot.news.model.enums.NewsArticleStatus;
import com.cryptopilot.news.repository.NewsArticleRepository;
import com.cryptopilot.news.repository.NewsConsoleRepository;
import com.cryptopilot.news.service.NewsArticleAdminService;
import com.cryptopilot.news.service.NewsSummaryService;
import java.time.Clock;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The news articles of the administration console (T-080b). Hiding and showing again are moderation decisions, so each
 * change is audited in its own transaction (BR-57); asking for a summary runs outside any, because the model takes
 * seconds and no connection waits on it.
 *
 * <p>Rule: NSF-11; BR-57; CR-04; D-128.
 */
@Service
@RequiredArgsConstructor
public class NewsArticleAdminServiceImpl implements NewsArticleAdminService {

    private final NewsConsoleRepository console;
    private final NewsArticleRepository articles;
    private final NewsSummaryService summaries;
    private final NewsSummaryProperties properties;
    private final AdminApi admin;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminNewsArticleResponse> list(String status, UUID sourceId, Integer page, Integer pageSize) {
        return PageResponse.of(
                console.page(status(status), sourceId, properties.maxAttempts(), Paging.of(page, pageSize)),
                NewsArticleAdminServiceImpl::response);
    }

    @Override
    @Transactional
    public AdminNewsArticleResponse hide(UUID adminId, UUID articleId) {
        article(articleId);
        if (articles.hide(articleId, adminId, clock.instant()) == 1) {
            audit(adminId, AuditAction.NEWS_ARTICLE_HIDDEN, articleId, false, true);
        }
        return response(article(articleId));
    }

    @Override
    @Transactional
    public AdminNewsArticleResponse restore(UUID adminId, UUID articleId) {
        article(articleId);
        if (articles.restore(articleId, clock.instant()) == 1) {
            audit(adminId, AuditAction.NEWS_ARTICLE_RESTORED, articleId, true, false);
        }
        return response(article(articleId));
    }

    @Override
    public AdminNewsArticleResponse summarize(UUID articleId) {
        summaries.summarizeNow(articleId);
        return response(article(articleId));
    }

    private void audit(UUID adminId, AuditAction action, UUID articleId, boolean hiddenBefore, boolean hiddenAfter) {
        admin.audit(new AuditEntry(
                adminId,
                action,
                AuditedEntity.NEWS_ARTICLE,
                articleId,
                Map.of("hidden", hiddenBefore),
                Map.of("hidden", hiddenAfter)));
    }

    private ConsoleArticle article(UUID articleId) {
        return console.find(articleId, properties.maxAttempts())
                .orElseThrow(() -> new ResourceNotFoundException("News article", articleId));
    }

    private static NewsArticleStatus status(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String name = status.strip().toUpperCase(Locale.ROOT);
        return Arrays.stream(NewsArticleStatus.values())
                .filter(value -> value.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new FieldValidationException(
                        "status is one of " + Arrays.toString(NewsArticleStatus.values()) + ", was " + status,
                        Map.of("status", "MSG15")));
    }

    private static AdminNewsArticleResponse response(ConsoleArticle article) {
        return new AdminNewsArticleResponse(
                article.articleId(),
                article.sourceId(),
                article.sourceName(),
                article.title(),
                article.aiTitle(),
                article.originalUrl(),
                article.excerpt(),
                article.aiSummary(),
                article.sentiment(),
                article.publishedAt(),
                article.collectedAt(),
                article.status(),
                article.summaryAttempts(),
                article.summaryError(),
                article.summaryAttemptedAt(),
                article.hiddenAt());
    }
}
