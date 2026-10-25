package com.cryptopilot.academy.service.impl;

import com.cryptopilot.academy.client.CoverStorage;
import com.cryptopilot.academy.client.CoverUploadTicket;
import com.cryptopilot.academy.dto.request.ArticleRequest;
import com.cryptopilot.academy.dto.request.TermRequest;
import com.cryptopilot.academy.dto.request.TopicRequest;
import com.cryptopilot.academy.dto.response.AdminArticleResponse;
import com.cryptopilot.academy.dto.response.AdminArticleSummaryResponse;
import com.cryptopilot.academy.dto.response.AdminTermResponse;
import com.cryptopilot.academy.dto.response.AdminTopicResponse;
import com.cryptopilot.academy.dto.response.CoverUploadResponse;
import com.cryptopilot.academy.entity.AcademyArticle;
import com.cryptopilot.academy.entity.AcademyTerm;
import com.cryptopilot.academy.entity.AcademyTopic;
import com.cryptopilot.academy.model.ArticleContent;
import com.cryptopilot.academy.model.ArticleListing;
import com.cryptopilot.academy.model.TermListing;
import com.cryptopilot.academy.model.TopicListing;
import com.cryptopilot.academy.model.enums.ArticleStatus;
import com.cryptopilot.academy.repository.AcademyAdminRepository;
import com.cryptopilot.academy.repository.AcademyArticleRepository;
import com.cryptopilot.academy.repository.AcademyTermRepository;
import com.cryptopilot.academy.repository.AcademyTopicRepository;
import com.cryptopilot.academy.service.AcademyAdminService;
import com.cryptopilot.admin.AdminApi;
import com.cryptopilot.admin.AuditEntry;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import com.cryptopilot.common.entity.BaseEntity;
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
 * topic's, lesson's or term's fields before and after (BR-57); a lesson's body is recorded by its length.
 *
 * <p>A change names the version the administrator edited. When the stored row has moved on, another administrator
 * saved in between and the change is refused rather than written over theirs (a lost update); the optimistic lock of
 * the row still guards the instant between this check and the write.
 *
 * <p>A cover photo is uploaded straight from the browser to the cover storage with a form signed here; the lesson then
 * names it, and only a photo of that storage is accepted, stored with the cover crop.
 *
 * <p>Rule: BR-57; T-114.
 */
@Service
@RequiredArgsConstructor
public class AcademyAdminServiceImpl implements AcademyAdminService {

    private final AcademyArticleRepository articles;
    private final AcademyTermRepository terms;
    private final AcademyTopicRepository topics;
    private final CoverStorage covers;
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
        requireVersion(article, request.version());
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
        requireVersion(term, request.version());
        if (terms.existsBySlugAndIdNot(request.slug(), termId)) {
            throw slugTaken(request.slug());
        }
        Map<String, Object> before = term.auditValues();
        term.edit(request.slug(), request.term(), request.definition(), articleId(request));
        terms.save(term);
        terms.flush();
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

    @Override
    @Transactional(readOnly = true)
    public List<AdminTopicResponse> topics() {
        return listings.topics().stream()
                .map(AcademyAdminServiceImpl::topicResponse)
                .toList();
    }

    @Override
    @Transactional
    public AdminTopicResponse createTopic(UUID adminId, TopicRequest request) {
        if (topics.existsBySlug(request.slug())) {
            throw slugTaken(request.slug());
        }
        AcademyTopic topic = topics.save(
                new AcademyTopic(request.slug(), request.name(), request.description(), request.sortOrder()));
        audit(
                adminId,
                AuditAction.CONFIGURATION_CREATED,
                AuditedEntity.ACADEMY_TOPIC,
                topic.getId(),
                null,
                topic.auditValues());
        return topicResponse(topic, 0);
    }

    @Override
    @Transactional
    public AdminTopicResponse updateTopic(UUID adminId, UUID topicId, TopicRequest request) {
        AcademyTopic topic = findTopic(topicId);
        requireVersion(topic, request.version());
        if (topics.existsBySlugAndIdNot(request.slug(), topicId)) {
            throw slugTaken(request.slug());
        }
        Map<String, Object> before = topic.auditValues();
        topic.edit(request.slug(), request.name(), request.description(), request.sortOrder());
        topics.save(topic);
        topics.flush();
        audit(
                adminId,
                AuditAction.CONFIGURATION_CHANGED,
                AuditedEntity.ACADEMY_TOPIC,
                topicId,
                before,
                topic.auditValues());
        return topicResponse(topic, listings.articleCount(topicId));
    }

    @Override
    @Transactional
    public void deleteTopic(UUID adminId, UUID topicId) {
        AcademyTopic topic = findTopic(topicId);
        if (listings.articleCount(topicId) > 0) {
            throw new BusinessException(
                    ErrorCode.ACADEMY_TOPIC_IN_USE, "topic " + topicId + " holds lessons; move or delete them first");
        }
        Map<String, Object> before = topic.auditValues();
        topics.delete(topic);
        audit(adminId, AuditAction.CONFIGURATION_DELETED, AuditedEntity.ACADEMY_TOPIC, topicId, before, null);
    }

    @Override
    public CoverUploadResponse coverUpload() {
        CoverUploadTicket ticket = covers.sign();
        return new CoverUploadResponse(
                ticket.uploadUrl(),
                ticket.apiKey(),
                ticket.timestamp(),
                ticket.signature(),
                ticket.publicId(),
                ticket.allowedFormats());
    }

    private AcademyTopic findTopic(UUID topicId) {
        return topics.findById(topicId).orElseThrow(() -> new ResourceNotFoundException("Topic", topicId));
    }

    /** Refuses a change made on another version than the stored one, or naming none. */
    private static void requireVersion(BaseEntity row, Long version) {
        if (version == null) {
            throw new FieldValidationException("version is required to change " + row, Map.of("version", "MSG01"));
        }
        if (version != row.getVersion()) {
            throw new BusinessException(
                    ErrorCode.ACADEMY_EDIT_CONFLICT,
                    row + " is at version " + row.getVersion() + ", the change was made on " + version);
        }
    }

    /** The cover address to store: a photo of the cover storage with the cover crop, or MSG01 under coverImageUrl. */
    private String cover(String coverImageUrl) {
        if (coverImageUrl == null) {
            return null;
        }
        return covers.accept(coverImageUrl)
                .orElseThrow(() -> new FieldValidationException(
                        "not a cover photo of this storage: " + coverImageUrl, Map.of("coverImageUrl", "MSG01")));
    }

    private static AdminTopicResponse topicResponse(TopicListing listing) {
        return new AdminTopicResponse(
                listing.id(),
                listing.slug(),
                listing.name(),
                listing.description(),
                listing.sortOrder(),
                listing.articles(),
                listing.version());
    }

    private static AdminTopicResponse topicResponse(AcademyTopic topic, long articles) {
        return new AdminTopicResponse(
                topic.getId(),
                topic.getSlug(),
                topic.getName(),
                topic.getDescription(),
                topic.getSortOrder(),
                articles,
                topic.getVersion());
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
                request.appLink(),
                cover(request.coverImageUrl()),
                request.coverCredit());
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
                article.getCoverImageUrl(),
                article.getCoverCredit(),
                article.getStatus(),
                article.getPublishedAt(),
                article.getCreatedAt(),
                article.getUpdatedAt(),
                article.getVersion());
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
                listing.id(),
                listing.slug(),
                listing.term(),
                listing.definition(),
                listing.articleSlug(),
                listing.version());
    }

    private static AdminTermResponse termResponse(AcademyTerm term, String articleSlug) {
        return new AdminTermResponse(
                term.getId(), term.getSlug(), term.getTerm(), term.getDefinition(), articleSlug, term.getVersion());
    }

    private static BusinessException slugTaken(String slug) {
        return new BusinessException(ErrorCode.ACADEMY_SLUG_TAKEN, "slug " + slug + " is taken");
    }

    private static BusinessException pathOrderTaken(int pathOrder) {
        return new BusinessException(
                ErrorCode.ACADEMY_PATH_ORDER_TAKEN, "place " + pathOrder + " of the path is taken");
    }
}
