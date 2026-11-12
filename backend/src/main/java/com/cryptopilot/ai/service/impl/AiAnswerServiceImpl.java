package com.cryptopilot.ai.service.impl;

import com.cryptopilot.ai.calculator.AnswerGuard;
import com.cryptopilot.ai.client.AiServiceClient;
import com.cryptopilot.ai.config.AiAssistantProperties;
import com.cryptopilot.ai.dto.response.AiAnswerPush;
import com.cryptopilot.ai.dto.response.MessageResponse;
import com.cryptopilot.ai.entity.AiConfiguration;
import com.cryptopilot.ai.entity.AiConversation;
import com.cryptopilot.ai.entity.AiMessage;
import com.cryptopilot.ai.model.AiChatAnswer;
import com.cryptopilot.ai.model.AiChatRequest;
import com.cryptopilot.ai.model.ChatTurn;
import com.cryptopilot.ai.model.PendingQuestion;
import com.cryptopilot.ai.model.enums.MessageStatus;
import com.cryptopilot.ai.repository.AiConfigurationRepository;
import com.cryptopilot.ai.repository.AiConversationRepository;
import com.cryptopilot.ai.repository.AiMessageRepository;
import com.cryptopilot.ai.service.AiAnswerService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The NSF-16 pipeline after a question is stored (T-073, TECHNICAL_DESIGN 7.14 steps 2–5).
 *
 * <p>The call to the AI Service can take up to the answer timeout, so it runs outside any transaction: the question,
 * the configuration and the history are read in one short transaction, the outcome is written in another. The outcome
 * is written only if the question is still PENDING, through one conditional update, so an answer that arrives after
 * the 60-second sweep failed the question is dropped rather than stored beside a FAILED question, and a redelivered
 * event cannot answer twice.
 *
 * <p>The history is the conversation's completed turns before the question, the most recent {@code historyTurns},
 * each cut to {@code historyTurnChars}; failed questions are not part of it. A question may be asked while another is
 * still pending, so the turns follow the questions, each answer after its own, not the order the answers arrived in.
 * Every answer ends with the active disclaimer (BR-51). Tokens are stored on the answer. A failure uses no quota
 * (BR-50), because the quota counts only pending and completed questions.
 *
 * <p>Rule: NSF-16, BR-50, BR-51; SRS 3.9.1, 3.9.2.
 */
@Service
public class AiAnswerServiceImpl implements AiAnswerService {

    /** The Trader's queue the outcome is pushed to; {@code WebSocketConfig.USER_QUEUES} admits it. */
    static final String QUEUE = "/queue/ai";

    private static final Logger log = LoggerFactory.getLogger(AiAnswerServiceImpl.class);

    private final AiMessageRepository messages;
    private final AiConversationRepository conversations;
    private final AiConfigurationRepository configurations;
    private final AiServiceClient client;
    private final AiAssistantProperties properties;
    private final JsonMapper json;
    private final SimpMessagingTemplate broker;
    private final Clock clock;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate readWrite;

    public AiAnswerServiceImpl(
            AiMessageRepository messages,
            AiConversationRepository conversations,
            AiConfigurationRepository configurations,
            AiServiceClient client,
            AiAssistantProperties properties,
            JsonMapper json,
            SimpMessagingTemplate broker,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.messages = messages;
        this.conversations = conversations;
        this.configurations = configurations;
        this.client = client;
        this.properties = properties;
        this.json = json;
        this.broker = broker;
        this.clock = clock;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.readWrite = new TransactionTemplate(transactions);
    }

    @Override
    public void answer(UUID questionId) {
        PendingQuestion pending = readOnly.execute(status -> prepare(questionId));
        if (pending == null) {
            return;
        }
        if (pending.request() == null) {
            log.error("NSF-16 question {} failed: no AI configuration is active", questionId);
            fail(pending);
            return;
        }
        AiChatAnswer answer;
        try {
            answer = client.chat(pending.request());
        } catch (RuntimeException failure) {
            log.warn("NSF-16 question {} failed: {}", questionId, failure.getMessage());
            fail(pending);
            return;
        }
        complete(pending, answer);
    }

    @Override
    public int failUnanswered() {
        Instant now = clock.instant();
        List<Object[]> failed =
                readWrite.execute(status -> messages.failPendingBefore(now.minus(properties.answerTimeout()), now));
        if (failed == null || failed.isEmpty()) {
            return 0;
        }
        for (Object[] row : failed) {
            UUID questionId = (UUID) row[0];
            UUID conversationId = (UUID) row[1];
            UUID userId = (UUID) row[2];
            log.warn("NSF-16 question {} failed: no answer within {}", questionId, properties.answerTimeout());
            send(userId, new AiAnswerPush(conversationId, questionId, MessageStatus.FAILED, null));
        }
        return failed.size();
    }

    private PendingQuestion prepare(UUID questionId) {
        Optional<AiMessage> found = messages.findById(questionId);
        if (found.isEmpty() || found.get().getStatus() != MessageStatus.PENDING) {
            return null;
        }
        AiMessage question = found.get();
        Optional<AiConversation> conversation = conversations.findById(question.getConversationId());
        if (conversation.isEmpty()) {
            return null;
        }
        UUID userId = conversation.get().getUserId();
        Optional<AiConfiguration> active = configurations.findByActiveTrue();
        if (active.isEmpty()) {
            return new PendingQuestion(questionId, question.getConversationId(), userId, null, null);
        }
        AiConfiguration config = active.get();
        AiChatRequest request = new AiChatRequest(
                config.getModelName(),
                config.getSystemPrompt(),
                config.getTemperature(),
                config.getMaxOutputTokens(),
                history(question),
                question.getContent(),
                context(question),
                properties.answerTimeout());
        return new PendingQuestion(questionId, question.getConversationId(), userId, request, config.getDisclaimer());
    }

    private List<ChatTurn> history(AiMessage question) {
        if (properties.historyTurns() == 0) {
            return List.of();
        }
        List<AiMessage> recent = messages.completedBefore(
                question.getConversationId(), question.getCreatedAt(), PageRequest.of(0, properties.historyTurns()));
        List<ChatTurn> turns = new ArrayList<>(recent.size());
        for (int i = recent.size() - 1; i >= 0; i--) {
            AiMessage turn = recent.get(i);
            turns.add(new ChatTurn(turn.getSenderRole(), cut(turn.getContent())));
        }
        return turns;
    }

    private String cut(String text) {
        int max = properties.historyTurnChars();
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private JsonNode context(AiMessage question) {
        if (question.getContextSnapshot() == null) {
            return null;
        }
        try {
            return json.readTree(question.getContextSnapshot());
        } catch (JacksonException e) {
            log.warn("NSF-16 question {}: its context snapshot is not JSON, sent without it", question.getId());
            return null;
        }
    }

    private void complete(PendingQuestion pending, AiChatAnswer answer) {
        String text = AnswerGuard.withDisclaimer(answer.text(), pending.disclaimer());
        readWrite.executeWithoutResult(status -> {
            Instant now = clock.instant();
            if (messages.decide(pending.questionId(), MessageStatus.COMPLETED.name(), now) == 0) {
                log.info("NSF-16 question {} was already decided; its late answer is dropped", pending.questionId());
                return;
            }
            AiMessage stored = messages.save(AiMessage.answer(
                    pending.conversationId(), pending.questionId(), text, answer.inputTokens(), answer.outputTokens()));
            MessageResponse body = AiMessages.of(stored);
            pushAfterCommit(
                    pending.userId(),
                    new AiAnswerPush(pending.conversationId(), pending.questionId(), MessageStatus.COMPLETED, body));
        });
    }

    private void fail(PendingQuestion pending) {
        readWrite.executeWithoutResult(status -> {
            if (messages.decide(pending.questionId(), MessageStatus.FAILED.name(), clock.instant()) == 0) {
                return;
            }
            pushAfterCommit(
                    pending.userId(),
                    new AiAnswerPush(pending.conversationId(), pending.questionId(), MessageStatus.FAILED, null));
        });
    }

    private void pushAfterCommit(UUID userId, AiAnswerPush body) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            send(userId, body);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send(userId, body);
            }
        });
    }

    private void send(UUID userId, AiAnswerPush body) {
        try {
            broker.convertAndSendToUser(userId.toString(), QUEUE, body);
        } catch (RuntimeException failure) {
            log.warn("NSF-16 push to user {} failed: {}", userId, failure.toString());
        }
    }
}
