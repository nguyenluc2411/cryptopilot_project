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
 * with (T-074, BR-51); an answer carries the token usage of the call that produced it (NSF-16) and the question it
 * answers, so the history keeps each answer after its own question when two were pending at once, and the news
 * articles it cited (T-074), so the conversation shows their links under it.
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

    /** The question an answer answers; {@code null} on a question and on an answer stored before V64. */
    @Column(name = "question_id", updatable = false)
    private UUID questionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_role", nullable = false, length = 32, updatable = false)
    private SenderRole senderRole;

    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_snapshot", updatable = false)
    private String contextSnapshot;

    /** The articles an answer cited, a JSON array; {@code null} on a question and on an answer that cites none. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sources", updatable = false)
    private String sources;

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
            UUID questionId,
            SenderRole role,
            String content,
            String contextSnapshot,
            Integer inputTokens,
            Integer outputTokens,
            MessageStatus status) {
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId must not be null");
        this.questionId = questionId;
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
                conversationId,
                null,
                SenderRole.USER,
                question.strip(),
                contextSnapshot,
                null,
                null,
                MessageStatus.PENDING);
    }

    /** The assistant's answer to {@code questionId}, stored once it is complete, citing no article. */
    public static AiMessage answer(
            UUID conversationId, UUID questionId, String text, int inputTokens, int outputTokens) {
        return answer(conversationId, questionId, text, null, inputTokens, outputTokens);
    }

    /**
     * The assistant's answer to {@code questionId} with the articles it cited, a JSON array, or {@code null} when it
     * cites none (T-074).
     */
    public static AiMessage answer(
            UUID conversationId, UUID questionId, String text, String sources, int inputTokens, int outputTokens) {
        AiMessage answer = new AiMessage(
                conversationId,
                Objects.requireNonNull(questionId, "questionId must not be null"),
                SenderRole.ASSISTANT,
                text,
                null,
                inputTokens,
                outputTokens,
                MessageStatus.COMPLETED);
        answer.sources = sources;
        return answer;
    }
}
