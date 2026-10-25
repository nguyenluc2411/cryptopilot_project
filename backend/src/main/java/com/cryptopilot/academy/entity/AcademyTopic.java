package com.cryptopilot.academy.entity;

import com.cryptopilot.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.Getter;

/**
 * A topic of the Academy, grouping lessons on the Academy home in its sort order.
 *
 * <p>Rule: T-114.
 */
@Getter
@Entity
@Table(name = "academy_topic")
@AttributeOverride(name = "id", column = @Column(name = "topic_id", nullable = false, updatable = false))
public class AcademyTopic extends BaseEntity {

    @Column(name = "slug", nullable = false, length = 80)
    private String slug;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** For JPA only. */
    protected AcademyTopic() {}

    /** A new topic. */
    public AcademyTopic(String slug, String name, String description, int sortOrder) {
        apply(slug, name, description, sortOrder);
    }

    /** Replaces the topic. */
    public void edit(String slug, String name, String description, int sortOrder) {
        apply(slug, name, description, sortOrder);
    }

    /** The fields an audit entry records. */
    public Map<String, Object> auditValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("slug", slug);
        values.put("name", name);
        values.put("description", description);
        values.put("sortOrder", sortOrder);
        return values;
    }

    private void apply(String slug, String name, String description, int sortOrder) {
        this.slug = Objects.requireNonNull(slug, "slug must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.sortOrder = sortOrder;
    }
}
