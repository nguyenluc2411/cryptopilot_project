package com.cryptopilot.ai.service;

import com.cryptopilot.ai.dto.request.AskQuestionRequest;
import com.cryptopilot.ai.dto.request.CreateConversationRequest;
import com.cryptopilot.ai.dto.request.RenameConversationRequest;
import com.cryptopilot.ai.dto.response.AskResponse;
import com.cryptopilot.ai.dto.response.ConversationResponse;
import com.cryptopilot.ai.dto.response.MessageResponse;
import com.cryptopilot.ai.dto.response.QuotaResponse;
import com.cryptopilot.ai.model.enums.ConversationTopic;
import com.cryptopilot.common.web.PageResponse;
import java.util.UUID;

/**
 * The AI Assistant of SCR-29 (UC-30, T-075): the Trader's conversations, the questions and the daily quota; implemented
 * by {@link com.cryptopilot.ai.service.impl.AiAssistantServiceImpl}. Another Trader's conversation, or a deleted one,
 * is reported as not found (MSG41).
 *
 * <p>Rule: UC-30, BR-50, BR-51, BR-52, BR-62; SRS 3.9.1.
 */
public interface AiAssistantService {

    /** The caller's conversations of one topic, or of every topic when {@code null}; last asked first, 20 per page. */
    PageResponse<ConversationResponse> conversations(
            UUID userId, ConversationTopic topic, Integer page, Integer pageSize);

    /**
     * Starts a conversation.
     *
     * @throws com.cryptopilot.common.exception.BusinessException {@code PLAN_FEATURE_NOT_INCLUDED} (MSG29) on a plan
     *     without the AI Assistant
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException for a pair that is not listed
     * @throws com.cryptopilot.common.exception.FieldValidationException MSG01 on {@code pairId} for MY_TRADING
     */
    ConversationResponse start(UUID userId, CreateConversationRequest request);

    /** Renames one of the caller's conversations; allowed on every plan (BR-52 keeps them the Trader's). */
    ConversationResponse rename(UUID userId, UUID conversationId, RenameConversationRequest request);

    /** Deletes one of the caller's conversations; its questions of today still count (BR-50). */
    void delete(UUID userId, UUID conversationId);

    /** The messages of one of the caller's conversations, newest first, 50 per page by default; readable on every plan. */
    PageResponse<MessageResponse> messages(UUID userId, UUID conversationId, Integer page, Integer pageSize);

    /**
     * Stores a question PENDING with its context snapshot and queues it for the AI Service (TECHNICAL_DESIGN 7.14
     * steps 1–2); the answer is pushed when it is ready.
     *
     * @throws com.cryptopilot.common.exception.BusinessException {@code PLAN_FEATURE_NOT_INCLUDED} (MSG29) on a plan
     *     without the AI Assistant (BR-50, BR-52) or for the performance summary below PREMIUM; {@code
     *     AI_DAILY_QUOTA_EXHAUSTED} (MSG30) with today's quota used up
     */
    AskResponse ask(UUID userId, UUID conversationId, AskQuestionRequest request);

    /** Today's quota of the caller. */
    QuotaResponse quota(UUID userId);
}
