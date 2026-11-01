package com.cryptopilot.ai.entity;

import com.cryptopilot.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * A Trader's conversation with the AI Assistant, optionally about one pair (SRS 3.9.1, AI_CONVERSATION). Deleting it
 * hides it rather than removing it, so its questions of the day keep counting against the quota (BR-50).
 *
 * <p>Rule: UC-30, BR-50, BR-52; SRS 3.9.1.
 */
@Getter
@Entity
@Table(name = "ai_conversation")
@AttributeOverride(name = "id", column = @Column(name = "conversation_id", nullable = false, updatable = false))
public class AiConversation extends BaseEntity {

    public static final int TITLE_MAX_LENGTH = 100;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "pair_id")
    private UUID pairId;

    @Column(name = "conversation_title", length = 255)
    private String title;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected AiConversation() {}

    private AiConversation(UUID userId, UUID pairId, String title) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.pairId = pairId;
        this.title = cut(title);
    }

    public static AiConversation start(UUID userId, UUID pairId, String title) {
        return new AiConversation(userId, pairId, title);
    }

    public void rename(String title) {
        this.title = cut(title);
    }

    /** Names an untitled conversation after its first question, and moves it to the top of the list. */
    public void questionAsked(String question, Instant at) {
        if (title == null) {
            this.title = cut(question);
        }
        markUpdated(at);
    }

    public void delete(Instant at) {
        if (deletedAt == null) {
            this.deletedAt = Objects.requireNonNull(at, "at must not be null");
        }
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    private static String cut(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= TITLE_MAX_LENGTH
                ? flat
                : flat.substring(0, TITLE_MAX_LENGTH - 1).strip() + "…";
    }
}
