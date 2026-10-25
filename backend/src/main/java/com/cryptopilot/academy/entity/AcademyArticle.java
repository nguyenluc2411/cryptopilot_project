package com.cryptopilot.academy.entity;

import com.cryptopilot.academy.model.ArticleContent;
import com.cryptopilot.academy.model.enums.ArticleLevel;
import com.cryptopilot.academy.model.enums.ArticleStatus;
import com.cryptopilot.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * A lesson of the Academy as an administrator writes it. A new lesson is a {@link ArticleStatus#DRAFT}; publishing
 * shows it to everyone and stamps the first publication, hiding takes it down without losing it.
 *
 * <p>Rule: T-114.
 */
@Getter
@Entity
@Table(name = "academy_article")
@AttributeOverride(name = "id", column = @Column(name = "article_id", nullable = false, updatable = false))
public class AcademyArticle extends BaseEntity {

    @Column(name = "topic_id", nullable = false)
    private UUID topicId;

    @Column(name = "slug", nullable = false, length = 120)
    private String slug;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "summary", nullable = false, length = 500)
    private String summary;

    @Column(name = "body", nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "article_level", nullable = false, length = 32)
    private ArticleLevel level;

    @Column(name = "reading_minutes", nullable = false)
    private int readingMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "article_status", nullable = false, length = 32)
    private ArticleStatus status;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "path_order")
    private Integer pathOrder;

    @Column(name = "app_link", length = 200)
    private String appLink;

    /** The first publication, kept when the lesson is hidden and published again. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    /** For JPA only. */
    protected AcademyArticle() {}

    private AcademyArticle(ArticleContent content, UUID author) {
        apply(content);
        this.status = ArticleStatus.DRAFT;
        this.createdBy = author;
        this.updatedBy = author;
    }

    /** A new lesson, not shown to readers until it is published. */
    public static AcademyArticle draft(ArticleContent content, UUID author) {
        return new AcademyArticle(content, author);
    }

    /** Replaces what the administrator writes; the status stays. */
    public void edit(ArticleContent content, UUID editor) {
        apply(content);
        this.updatedBy = editor;
    }

    /**
     * Shows the lesson to everyone; the first publication is stamped once, to the microsecond the column keeps, so the
     * instant answered now is the one read back later.
     */
    public void publish(Instant now, UUID editor) {
        this.status = ArticleStatus.PUBLISHED;
        if (publishedAt == null) {
            this.publishedAt =
                    Objects.requireNonNull(now, "now must not be null").truncatedTo(ChronoUnit.MICROS);
        }
        this.updatedBy = editor;
    }

    /** Takes the lesson down; it stays, with its readers' progress. */
    public void hide(UUID editor) {
        this.status = ArticleStatus.HIDDEN;
        this.updatedBy = editor;
    }

    /** The fields an audit entry records; the body by its length, as it can be long. */
    public Map<String, Object> auditValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("topicId", topicId);
        values.put("slug", slug);
        values.put("title", title);
        values.put("level", level.name());
        values.put("status", status.name());
        values.put("readingMinutes", readingMinutes);
        values.put("sortOrder", sortOrder);
        values.put("pathOrder", pathOrder);
        values.put("appLink", appLink);
        values.put("bodyLength", body.length());
        return values;
    }

    private void apply(ArticleContent content) {
        this.topicId = Objects.requireNonNull(content.topicId(), "topicId must not be null");
        this.slug = Objects.requireNonNull(content.slug(), "slug must not be null");
        this.title = Objects.requireNonNull(content.title(), "title must not be null");
        this.summary = Objects.requireNonNull(content.summary(), "summary must not be null");
        this.body = Objects.requireNonNull(content.body(), "body must not be null");
        this.level = Objects.requireNonNull(content.level(), "level must not be null");
        this.readingMinutes = content.readingMinutes();
        this.sortOrder = content.sortOrder();
        this.pathOrder = content.pathOrder();
        this.appLink = content.appLink();
    }
}
