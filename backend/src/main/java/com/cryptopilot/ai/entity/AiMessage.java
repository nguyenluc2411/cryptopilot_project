package com.cryptopilot.ai.entity;

import com.cryptopilot.ai.model.enums.MessageStatus;
import com.cryptopilot.ai.model.enums.SenderRole;
import com.cryptopilot.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One question or answer of an AI conversation (AI_MESSAGE). A question carries the context snapshot it was asked
 * with (T-074, BR-51); an answer carries the token usage of the call that produced it (NSF-16).
 *
 * <p>Rule: BR-50, BR-51; SRS 3.9.1, 3.9.2; TECHNICAL_DESIGN 7.14.
 */
@Getter
@Entity
@Table(name = "ai_message")
@AttributeOverride(name = "id", column = @Column(name = "message_id", nullable = false, updatable = false))
public class AiMessage extends BaseEntity {

    /** SRS 3.9.1: a question is 1–1,000 characters. */
    public static final int QUESTION_MAX_LENGTH = 1_000;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_role", nullable = false, length = 32, updatable = false)
    private SenderRole senderRole;

    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_snapshot", updatable = false)
    private String contextSnapshot;

    @Column(name = "input_tokens", updatable = false)
    private Integer inputTokens;

    @Column(name = "output_tokens", updatable = false)
    private Integer outputTokens;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_status", nullable = false, length = 32)
    private MessageStatus status;

    protected AiMessage() {}

    private AiMessage(
            UUID conversationId,
            SenderRole role,
            String content,
            String contextSnapshot,
            Integer inputTokens,
            Integer outputTokens,
            MessageStatus status) {
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId must not be null");
        this.senderRole = Objects.requireNonNull(role, "role must not be null");
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.contextSnapshot = contextSnapshot;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    /** A Trader's question, PENDING until it is answered or fails. */
    public static AiMessage question(UUID conversationId, String question, String contextSnapshot) {
        return new AiMessage(
                conversationId, SenderRole.USER, question.strip(), contextSnapshot, null, null, MessageStatus.PENDING);
    }

    /** The assistant's answer, stored once it is complete. */
    public static AiMessage answer(UUID conversationId, String text, int inputTokens, int outputTokens) {
        return new AiMessage(
                conversationId, SenderRole.ASSISTANT, text, null, inputTokens, outputTokens, MessageStatus.COMPLETED);
    }
}
