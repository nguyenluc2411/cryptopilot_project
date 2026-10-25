package com.cryptopilot.academy.entity;

import com.cryptopilot.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * A term of the Academy glossary, optionally pointing at the lesson that explains it.
 *
 * <p>Rule: T-114.
 */
@Getter
@Entity
@Table(name = "academy_term")
@AttributeOverride(name = "id", column = @Column(name = "term_id", nullable = false, updatable = false))
public class AcademyTerm extends BaseEntity {

    @Column(name = "slug", nullable = false, length = 120)
    private String slug;

    @Column(name = "term", nullable = false, length = 120)
    private String term;

    @Column(name = "definition", nullable = false, length = 1000)
    private String definition;

    @Column(name = "article_id")
    private UUID articleId;

    /** For JPA only. */
    protected AcademyTerm() {}

    /** A new term. */
    public AcademyTerm(String slug, String term, String definition, UUID articleId) {
        apply(slug, term, definition, articleId);
    }

    /** Replaces the term. */
    public void edit(String slug, String term, String definition, UUID articleId) {
        apply(slug, term, definition, articleId);
    }

    private void apply(String slug, String term, String definition, UUID articleId) {
        this.slug = Objects.requireNonNull(slug, "slug must not be null");
        this.term = Objects.requireNonNull(term, "term must not be null");
        this.definition = Objects.requireNonNull(definition, "definition must not be null");
        this.articleId = articleId;
    }

    /** The fields an audit entry records. */
    public Map<String, Object> auditValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("slug", slug);
        values.put("term", term);
        values.put("definition", definition);
        values.put("articleId", articleId);
        return values;
    }
}
