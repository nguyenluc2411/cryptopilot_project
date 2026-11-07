package com.cryptopilot.ai.service.impl;

import com.cryptopilot.ai.calculator.QuotaDay;
import com.cryptopilot.ai.dto.request.AskQuestionRequest;
import com.cryptopilot.ai.dto.request.CreateConversationRequest;
import com.cryptopilot.ai.dto.request.RenameConversationRequest;
import com.cryptopilot.ai.dto.response.AskResponse;
import com.cryptopilot.ai.dto.response.ConversationResponse;
import com.cryptopilot.ai.dto.response.MessageResponse;
import com.cryptopilot.ai.dto.response.QuotaResponse;
import com.cryptopilot.ai.entity.AiConversation;
import com.cryptopilot.ai.entity.AiMessage;
import com.cryptopilot.ai.event.AiQuestionAsked;
import com.cryptopilot.ai.model.AiContextSelection;
import com.cryptopilot.ai.model.enums.ConversationTopic;
import com.cryptopilot.ai.repository.AiConversationRepository;
import com.cryptopilot.ai.repository.AiMessageRepository;
import com.cryptopilot.ai.service.AiAssistantService;
import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.market.MarketApi;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The conversations, the questions and the quota of the AI Assistant (T-075).
 *
 * <p>A question is counted and stored under the Trader's own AI lock (D-63): two questions sent at once at
 * {@code quota − 1} would otherwise both see room. The quota is read from the plan at that moment, so an upgrade
 * applies at once and the questions already asked that day are counted (BR-50); a plan without the assistant is
 * refused with MSG29 before anything is stored, which is what keeps an expired plan's conversations read-only (BR-52).
 *
 * <p>A conversation keeps its topic, and a question carries only the data of that topic: a MARKET one never a plan or
 * the performance summary, a MY_TRADING one never a pair of its own (its plan names the pair). Anything else is
 * refused with MSG01 on the field, so the two tabs of SCR-29 cannot mix (BR-51).
 *
 * <p>Rule: UC-30, BR-50, BR-51, BR-52, BR-62; SRS 3.9.1; D-61, D-63.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AiAssistantServiceImpl implements AiAssistantService {

    /** The scope of the per-user advisory lock the questions are counted under. */
    static final String LOCK_SCOPE = "ai-question";

    private static final int DEFAULT_MESSAGE_PAGE = 50;

    private final AiConversationRepository conversations;
    private final AiMessageRepository messages;
    private final ContextSnapshotBuilder snapshots;
    private final EntitlementApi entitlements;
    private final MarketApi market;
    private final UserLock locks;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ConversationResponse> conversations(
            UUID userId, ConversationTopic topic, Integer page, Integer pageSize) {
        return PageResponse.of(
                topic == null
                        ? conversations.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
                                userId, Paging.of(page, pageSize))
                        : conversations.findByUserIdAndTopicAndDeletedAtIsNullOrderByUpdatedAtDesc(
                                userId, topic, Paging.of(page, pageSize)),
                AiMessages::of);
    }

    @Override
    @Transactional
    public ConversationResponse start(UUID userId, CreateConversationRequest request) {
        entitlements.requireFeature(userId, Feature.AI_CHAT_DAILY);
        ConversationTopic topic = request.topicOrGeneral();
        if (topic == ConversationTopic.MY_TRADING && request.pairId() != null) {
            throw new FieldValidationException(
                    "a MY_TRADING conversation is not about a pair", Map.of("pairId", "MSG01"));
        }
        if (request.pairId() != null
                && market.pairListings(List.of(request.pairId())).isEmpty()) {
            throw new ResourceNotFoundException("CryptoPair", request.pairId());
        }
        return AiMessages.of(
                conversations.save(AiConversation.start(userId, topic, request.pairId(), request.title())));
    }

    @Override
    @Transactional
    public ConversationResponse rename(UUID userId, UUID conversationId, RenameConversationRequest request) {
        AiConversation conversation = owned(userId, conversationId);
        conversation.rename(request.title());
        return AiMessages.of(conversations.save(conversation));
    }

    @Override
    @Transactional
    public void delete(UUID userId, UUID conversationId) {
        AiConversation conversation = owned(userId, conversationId);
        conversation.delete(clock.instant());
        conversations.save(conversation);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<MessageResponse> messages(UUID userId, UUID conversationId, Integer page, Integer pageSize) {
        owned(userId, conversationId);
        return PageResponse.of(
                messages.findByConversationIdOrderByCreatedAtDesc(
                        conversationId, Paging.of(page, pageSize == null ? DEFAULT_MESSAGE_PAGE : pageSize)),
                AiMessages::of);
    }

    @Override
    @Transactional
    public AskResponse ask(UUID userId, UUID conversationId, AskQuestionRequest request) {
        AiConversation conversation = owned(userId, conversationId);
        requireTopicData(conversation.getTopic(), request);
        locks.lock(LOCK_SCOPE, userId);
        Instant now = clock.instant();
        long used = messages.countQuestionsSince(userId, QuotaDay.start(now));
        entitlements.checkDailyQuota(userId, Feature.AI_CHAT_DAILY, used);
        AiContextSelection selection = new AiContextSelection(
                request.pairId() != null ? request.pairId() : conversation.getPairId(),
                request.market(),
                request.timeframe(),
                request.planId(),
                Boolean.TRUE.equals(request.includePerformance()));
        String snapshot = snapshots.toJson(snapshots.build(userId, selection));
        AiMessage question = messages.save(AiMessage.question(conversation.getId(), request.question(), snapshot));
        conversation.questionAsked(request.question(), now);
        conversations.save(conversation);
        events.publishEvent(new AiQuestionAsked(question.getId(), conversation.getId(), userId));
        return new AskResponse(AiMessages.of(question), quotaOf(userId, used + 1, now));
    }

    @Override
    @Transactional(readOnly = true)
    public QuotaResponse quota(UUID userId) {
        Instant now = clock.instant();
        return quotaOf(userId, messages.countQuestionsSince(userId, QuotaDay.start(now)), now);
    }

    private QuotaResponse quotaOf(UUID userId, long used, Instant now) {
        Integer limit = entitlements.getLimit(userId, Feature.AI_CHAT_DAILY);
        Integer remaining = limit == null ? null : (int) Math.max(0, limit - used);
        boolean canAsk = limit == null || used < limit;
        return new QuotaResponse(
                limit,
                used,
                remaining,
                QuotaDay.resetsAt(now),
                canAsk,
                entitlements.hasFeature(userId, Feature.AI_PERFORMANCE_CONTEXT));
    }

    /** Refuses the context fields that do not belong to the conversation's topic, each with MSG01 (BR-51). */
    private static void requireTopicData(ConversationTopic topic, AskQuestionRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (topic == ConversationTopic.MARKET) {
            if (request.planId() != null) {
                errors.put("planId", "MSG01");
            }
            if (Boolean.TRUE.equals(request.includePerformance())) {
                errors.put("includePerformance", "MSG01");
            }
        } else if (topic == ConversationTopic.MY_TRADING) {
            if (request.pairId() != null) {
                errors.put("pairId", "MSG01");
            }
            if (request.market() != null) {
                errors.put("market", "MSG01");
            }
            if (request.timeframe() != null) {
                errors.put("timeframe", "MSG01");
            }
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException("the question carries data outside the " + topic + " topic", errors);
        }
    }

    private AiConversation owned(UUID userId, UUID conversationId) {
        return conversations
                .findByIdAndUserIdAndDeletedAtIsNull(conversationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("AiConversation", conversationId));
    }
}
