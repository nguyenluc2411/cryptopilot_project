package com.cryptopilot.academy.service.impl;

import com.cryptopilot.academy.dto.request.ArticleRequest;
import com.cryptopilot.academy.dto.request.TermRequest;
import com.cryptopilot.academy.dto.response.AdminArticleResponse;
import com.cryptopilot.academy.dto.response.AdminArticleSummaryResponse;
import com.cryptopilot.academy.dto.response.AdminTermResponse;
import com.cryptopilot.academy.entity.AcademyArticle;
import com.cryptopilot.academy.entity.AcademyTerm;
import com.cryptopilot.academy.model.ArticleContent;
import com.cryptopilot.academy.model.ArticleListing;
import com.cryptopilot.academy.model.TermListing;
import com.cryptopilot.academy.model.enums.ArticleStatus;
import com.cryptopilot.academy.repository.AcademyAdminRepository;
import com.cryptopilot.academy.repository.AcademyArticleRepository;
import com.cryptopilot.academy.repository.AcademyTermRepository;
import com.cryptopilot.academy.service.AcademyAdminService;
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
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Academy in the administration console. Each change writes its audit entry in the same transaction, with the
 * lesson's or term's fields before and after (BR-57); a lesson's body is recorded by its length.
 *
 * <p>Rule: BR-57; T-114.
 */
@Service
@RequiredArgsConstructor
public class AcademyAdminServiceImpl implements AcademyAdminService {

    private final AcademyArticleRepository articles;
    private final AcademyTermRepository terms;
    private final AcademyAdminRepository listings;
    private final AdminApi admin;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminArticleSummaryResponse> articles(
            ArticleStatus status, String topic, String q, Integer page, Integer pageSize) {
        return PageResponse.of(
                listings.articles(status, SearchText.blankToNull(topic), SearchText.of(q), Paging.of(page, pageSize)),
                AcademyAdminServiceImpl::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminArticleResponse article(UUID articleId) {
        return response(find(articleId));
    }

    @Override
    @Transactional
    public AdminArticleResponse create(UUID adminId, ArticleRequest request) {
        if (articles.existsBySlug(request.slug())) {
            throw slugTaken(request.slug());
        }
        if (request.pathOrder() != null && articles.existsByPathOrder(request.pathOrder())) {
            throw pathOrderTaken(request.pathOrder());
        }
        AcademyArticle article = articles.save(AcademyArticle.draft(content(request), adminId));
        audit(
                adminId,
                AuditAction.CONFIGURATION_CREATED,
                AuditedEntity.ACADEMY_ARTICLE,
                article.getId(),
                null,
                article.auditValues());
        return response(article);
    }

    @Override
    @Transactional
    public AdminArticleResponse update(UUID adminId, UUID articleId, ArticleRequest request) {
        AcademyArticle article = find(articleId);
        if (articles.existsBySlugAndIdNot(request.slug(), articleId)) {
            throw slugTaken(request.slug());
        }
        if (request.pathOrder() != null && articles.existsByPathOrderAndIdNot(request.pathOrder(), articleId)) {
            throw pathOrderTaken(request.pathOrder());
        }
        Map<String, Object> before = article.auditValues();
        article.edit(content(request), adminId);
        return saved(adminId, article, before);
    }

    @Override
    @Transactional
    public AdminArticleResponse publish(UUID adminId, UUID articleId) {
        AcademyArticle article = find(articleId);
        if (article.getStatus() == ArticleStatus.PUBLISHED) {
            return response(article);
        }
        Map<String, Object> before = article.auditValues();
        article.publish(clock.instant(), adminId);
        return saved(adminId, article, before);
    }

    @Override
    @Transactional
    public AdminArticleResponse hide(UUID adminId, UUID articleId) {
        AcademyArticle article = find(articleId);
        if (article.getStatus() == ArticleStatus.HIDDEN) {
            return response(article);
        }
        Map<String, Object> before = article.auditValues();
        article.hide(adminId);
        return saved(adminId, article, before);
    }

    @Override
    @Transactional
    public void delete(UUID adminId, UUID articleId) {
        AcademyArticle article = find(articleId);
        if (article.getStatus() == ArticleStatus.PUBLISHED) {
            throw new BusinessException(
                    ErrorCode.ACADEMY_ARTICLE_PUBLISHED, "lesson " + articleId + " is published; hide it first");
        }
        Map<String, Object> before = article.auditValues();
        articles.delete(article);
        audit(adminId, AuditAction.CONFIGURATION_DELETED, AuditedEntity.ACADEMY_ARTICLE, articleId, before, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminTermResponse> terms(String q) {
        return listings.terms(SearchText.of(q)).stream()
                .map(AcademyAdminServiceImpl::termResponse)
                .toList();
    }

    @Override
    @Transactional
    public AdminTermResponse createTerm(UUID adminId, TermRequest request) {
        if (terms.existsBySlug(request.slug())) {
            throw slugTaken(request.slug());
        }
        AcademyTerm term =
                terms.save(new AcademyTerm(request.slug(), request.term(), request.definition(), articleId(request)));
        audit(
                adminId,
                AuditAction.CONFIGURATION_CREATED,
                AuditedEntity.ACADEMY_TERM,
                term.getId(),
                null,
                term.auditValues());
        return termResponse(term, request.articleSlug());
    }

    @Override
    @Transactional
    public AdminTermResponse updateTerm(UUID adminId, UUID termId, TermRequest request) {
        AcademyTerm term = terms.findById(termId).orElseThrow(() -> new ResourceNotFoundException("Term", termId));
        if (terms.existsBySlugAndIdNot(request.slug(), termId)) {
            throw slugTaken(request.slug());
        }
        Map<String, Object> before = term.auditValues();
        term.edit(request.slug(), request.term(), request.definition(), articleId(request));
        terms.save(term);
        audit(
                adminId,
                AuditAction.CONFIGURATION_CHANGED,
                AuditedEntity.ACADEMY_TERM,
                termId,
                before,
                term.auditValues());
        return termResponse(term, request.articleSlug());
    }

    @Override
    @Transactional
    public void deleteTerm(UUID adminId, UUID termId) {
        AcademyTerm term = terms.findById(termId).orElseThrow(() -> new ResourceNotFoundException("Term", termId));
        Map<String, Object> before = term.auditValues();
        terms.delete(term);
        audit(adminId, AuditAction.CONFIGURATION_DELETED, AuditedEntity.ACADEMY_TERM, termId, before, null);
    }

    private AcademyArticle find(UUID articleId) {
        return articles.findById(articleId).orElseThrow(() -> new ResourceNotFoundException("Article", articleId));
    }

    private AdminArticleResponse saved(UUID adminId, AcademyArticle article, Map<String, Object> before) {
        articles.save(article);
        articles.flush();
        audit(
                adminId,
                AuditAction.CONFIGURATION_CHANGED,
                AuditedEntity.ACADEMY_ARTICLE,
                article.getId(),
                before,
                article.auditValues());
        return response(article);
    }

    private void audit(
            UUID adminId,
            AuditAction action,
            AuditedEntity entity,
            UUID id,
            Map<String, Object> before,
            Map<String, Object> after) {
        admin.audit(new AuditEntry(adminId, action, entity, id, before, after));
    }

    private ArticleContent content(ArticleRequest request) {
        UUID topicId = listings.topicId(request.topic())
                .orElseThrow(
                        () -> new FieldValidationException("no topic " + request.topic(), Map.of("topic", "MSG01")));
        return new ArticleContent(
                topicId,
                request.slug(),
                request.title(),
                request.summary(),
                request.body(),
                request.level(),
                request.readingMinutes(),
                request.sortOrder(),
                request.pathOrder(),
                request.appLink());
    }

    private UUID articleId(TermRequest request) {
        if (request.articleSlug() == null) {
            return null;
        }
        return articles.findBySlug(request.articleSlug())
                .map(AcademyArticle::getId)
                .orElseThrow(() -> new FieldValidationException(
                        "no lesson " + request.articleSlug(), Map.of("articleSlug", "MSG01")));
    }

    private AdminArticleResponse response(AcademyArticle article) {
        return new AdminArticleResponse(
                article.getId(),
                listings.topicSlug(article.getTopicId()),
                article.getSlug(),
                article.getTitle(),
                article.getSummary(),
                article.getBody(),
                article.getLevel(),
                article.getReadingMinutes(),
                article.getSortOrder(),
                article.getPathOrder(),
                article.getAppLink(),
                article.getStatus(),
                article.getPublishedAt(),
                article.getCreatedAt(),
                article.getUpdatedAt());
    }

    private static AdminArticleSummaryResponse summary(ArticleListing listing) {
        return new AdminArticleSummaryResponse(
                listing.id(),
                listing.slug(),
                listing.title(),
                listing.topicSlug(),
                listing.topicName(),
                listing.level(),
                listing.status(),
                listing.sortOrder(),
                listing.pathOrder(),
                listing.publishedAt(),
                listing.updatedAt());
    }

    private static AdminTermResponse termResponse(TermListing listing) {
        return new AdminTermResponse(
                listing.id(), listing.slug(), listing.term(), listing.definition(), listing.articleSlug());
    }

    private static AdminTermResponse termResponse(AcademyTerm term, String articleSlug) {
        return new AdminTermResponse(term.getId(), term.getSlug(), term.getTerm(), term.getDefinition(), articleSlug);
    }

    private static BusinessException slugTaken(String slug) {
        return new BusinessException(ErrorCode.ACADEMY_SLUG_TAKEN, "slug " + slug + " is taken");
    }

    private static BusinessException pathOrderTaken(int pathOrder) {
        return new BusinessException(
                ErrorCode.ACADEMY_PATH_ORDER_TAKEN, "place " + pathOrder + " of the path is taken");
    }
}
