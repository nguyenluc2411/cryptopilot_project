package com.cryptopilot.ai.repository;

import com.cryptopilot.ai.entity.AiMessage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The messages of the conversations. A question changes status only through the conditional updates below, so the
 * answer, the failure and the 60-second sweep cannot overwrite each other: whichever reaches a PENDING question first
 * decides it.
 */
public interface AiMessageRepository extends Repository<AiMessage, UUID> {

    @Transactional(readOnly = true)
    Optional<AiMessage> findById(UUID messageId);

    @Transactional(readOnly = true)
    Page<AiMessage> findByConversationIdOrderByCreatedAtDesc(UUID conversationId, Pageable page);

    /**
     * The completed turns of a conversation before an instant, newest first: the history sent with a question
     * (TECHNICAL_DESIGN 7.14 step 2). A failed question and the pending ones are not part of it.
     */
    @Transactional(readOnly = true)
    @Query("""
            select m from AiMessage m
            where m.conversationId = :conversationId
              and m.createdAt < :before
              and m.status = com.cryptopilot.ai.model.enums.MessageStatus.COMPLETED
            order by m.createdAt desc""")
    List<AiMessage> completedBefore(
            @Param("conversationId") UUID conversationId, @Param("before") Instant before, Pageable page);

    /**
     * The Trader's questions that count against today's quota (BR-50, D-61): pending or completed, never failed,
     * since {@code from}, deleted conversations included.
     */
    @Transactional(readOnly = true)
    @Query(value = """
                    select count(*) from ai_message m
                    join ai_conversation c on c.conversation_id = m.conversation_id
                    where c.user_id = :userId
                      and m.sender_role = 'USER'
                      and m.message_status in ('PENDING', 'COMPLETED')
                      and m.created_at >= :from""", nativeQuery = true)
    long countQuestionsSince(@Param("userId") UUID userId, @Param("from") Instant from);

    /** Moves a question from PENDING to {@code status}; 0 when it was already decided. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
                    update ai_message set message_status = :status, updated_at = :at, version = version + 1
                    where message_id = :id and message_status = 'PENDING'""", nativeQuery = true)
    int decide(@Param("id") UUID questionId, @Param("status") String status, @Param("at") Instant at);

    /**
     * Fails every question still pending since before {@code cutoff} (SRS 3.9.1: after 60 seconds the message becomes
     * FAILED) and returns each one with its conversation and Trader, to push the outcome. A statement that returns
     * rows, so it runs as a query, inside the caller's read-write transaction.
     */
    @Query(value = """
                    update ai_message m set message_status = 'FAILED', updated_at = :at, version = m.version + 1
                    from ai_conversation c
                    where c.conversation_id = m.conversation_id
                      and m.message_status = 'PENDING'
                      and m.created_at < :cutoff
                    returning m.message_id, m.conversation_id, c.user_id""", nativeQuery = true)
    List<Object[]> failPendingBefore(@Param("cutoff") Instant cutoff, @Param("at") Instant at);

    AiMessage save(AiMessage message);
}
