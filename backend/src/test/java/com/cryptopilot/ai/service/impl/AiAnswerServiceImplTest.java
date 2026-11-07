package com.cryptopilot.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.ai.client.AiServiceClient;
import com.cryptopilot.ai.config.AiAssistantProperties;
import com.cryptopilot.ai.dto.response.AiAnswerPush;
import com.cryptopilot.ai.entity.AiConfiguration;
import com.cryptopilot.ai.entity.AiConversation;
import com.cryptopilot.ai.entity.AiMessage;
import com.cryptopilot.ai.model.AiChatAnswer;
import com.cryptopilot.ai.model.AiChatRequest;
import com.cryptopilot.ai.model.AiConfigurationSettings;
import com.cryptopilot.ai.model.enums.ConversationTopic;
import com.cryptopilot.ai.model.enums.MessageStatus;
import com.cryptopilot.ai.model.enums.SenderRole;
import com.cryptopilot.ai.repository.AiConfigurationRepository;
import com.cryptopilot.ai.repository.AiConversationRepository;
import com.cryptopilot.ai.repository.AiMessageRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The answer pipeline of NSF-16 around its edges: questions that are gone or already decided, no active configuration,
 * an AI Service failure, an answer that arrives after the question was decided, and the history sent with a question.
 * Outside a transaction synchronization the push goes out at once.
 */
class AiAnswerServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

    private final AiMessageRepository messages = mock(AiMessageRepository.class);
    private final AiConversationRepository conversations = mock(AiConversationRepository.class);
    private final AiConfigurationRepository configurations = mock(AiConfigurationRepository.class);
    private final AiServiceClient client = mock(AiServiceClient.class);
    private final SimpMessagingTemplate broker = mock(SimpMessagingTemplate.class);
    private final UUID userId = UUID.randomUUID();
    private final AiConversation conversation = AiConversation.start(userId, ConversationTopic.MARKET, null, null);

    private AiAnswerServiceImpl service(int historyTurns, int historyTurnChars) {
        return new AiAnswerServiceImpl(
                messages,
                conversations,
                configurations,
                client,
                new AiAssistantProperties(
                        historyTurns, historyTurnChars, Duration.ofSeconds(60), true, Duration.ofSeconds(15)),
                JsonMapper.builder().build(),
                broker,
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(PlatformTransactionManager.class));
    }

    private AiMessage question(String snapshot) {
        AiMessage question = AiMessage.question(conversation.getId(), "RSI là gì?", snapshot);
        when(messages.findById(question.getId())).thenReturn(Optional.of(question));
        when(conversations.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        return question;
    }

    private void activeConfiguration() {
        AiConfiguration config = AiConfiguration.create(
                new AiConfigurationSettings("gemini-test", "prompt", new BigDecimal("0.30"), 1024, "Disclaimer."),
                UUID.randomUUID());
        when(configurations.findByActiveTrue()).thenReturn(Optional.of(config));
    }

    @Test
    void NSF16_aQuestionThatIsGone_isLeftAlone() {
        UUID missing = UUID.randomUUID();
        when(messages.findById(missing)).thenReturn(Optional.empty());

        service(10, 2000).answer(missing);

        verifyNoInteractions(client, broker);
    }

    @Test
    void NSF16_aQuestionAlreadyDecided_isLeftAlone() {
        AiMessage answered = AiMessage.answer(conversation.getId(), "done", 1, 1);
        when(messages.findById(answered.getId())).thenReturn(Optional.of(answered));

        service(10, 2000).answer(answered.getId());

        verifyNoInteractions(client, broker);
    }

    @Test
    void NSF16_aQuestionWhoseConversationIsGone_isLeftAlone() {
        AiMessage question = AiMessage.question(UUID.randomUUID(), "x", null);
        when(messages.findById(question.getId())).thenReturn(Optional.of(question));
        when(conversations.findById(question.getConversationId())).thenReturn(Optional.empty());

        service(10, 2000).answer(question.getId());

        verifyNoInteractions(client, broker);
    }

    @Test
    void NSF16_withoutAnActiveConfiguration_theQuestionFails_andTheFailureIsPushed() {
        AiMessage question = question(null);
        when(configurations.findByActiveTrue()).thenReturn(Optional.empty());
        when(messages.decide(eq(question.getId()), eq("FAILED"), any())).thenReturn(1);

        service(10, 2000).answer(question.getId());

        verify(client, never()).chat(any());
        ArgumentCaptor<AiAnswerPush> push = ArgumentCaptor.forClass(AiAnswerPush.class);
        verify(broker).convertAndSendToUser(eq(userId.toString()), eq(AiAnswerServiceImpl.QUEUE), push.capture());
        assertThat(push.getValue().status()).isEqualTo(MessageStatus.FAILED);
    }

    @Test
    void NSF16_anAiServiceFailure_onAQuestionDecidedMeanwhile_pushesNothing() {
        AiMessage question = question(null);
        activeConfiguration();
        when(client.chat(any())).thenThrow(new IllegalStateException("down"));
        when(messages.decide(eq(question.getId()), eq("FAILED"), any())).thenReturn(0);

        service(10, 2000).answer(question.getId());

        verifyNoInteractions(broker);
    }

    @Test
    void NSF16_anAnswerArrivingAfterTheQuestionWasDecided_isDropped_andNoHistoryIsSentWhenTurnsAreZero() {
        AiMessage question = question("not json");
        activeConfiguration();
        when(client.chat(any())).thenReturn(new AiChatAnswer("RSI đo động lượng.", "gemini-test", 10, 5));
        when(messages.decide(eq(question.getId()), eq("COMPLETED"), any())).thenReturn(0);

        service(0, 2000).answer(question.getId());

        ArgumentCaptor<AiChatRequest> request = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(client).chat(request.capture());
        assertThat(request.getValue().history()).isEmpty();
        assertThat(request.getValue().context()).isNull();
        verify(messages, never()).completedBefore(any(), any(), any());
        verify(messages, never()).save(any());
        verifyNoInteractions(broker);
    }

    @Test
    void NSF16_aLongEarlierTurn_isCut_andTheAnswerIsStoredWithTheDisclaimerAndPushed() {
        AiMessage question = question("{\"version\":1}");
        activeConfiguration();
        String longTurn = "x".repeat(250);
        when(messages.completedBefore(any(), any(), any()))
                .thenReturn(List.of(AiMessage.answer(conversation.getId(), longTurn, 1, 1)));
        when(client.chat(any())).thenReturn(new AiChatAnswer("RSI đo động lượng.", "gemini-test", 10, 5));
        when(messages.decide(eq(question.getId()), eq("COMPLETED"), any())).thenReturn(1);
        when(messages.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service(2, 200).answer(question.getId());

        ArgumentCaptor<AiChatRequest> request = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(client).chat(request.capture());
        assertThat(request.getValue().history()).hasSize(1);
        assertThat(request.getValue().history().get(0).role()).isEqualTo(SenderRole.ASSISTANT);
        assertThat(request.getValue().history().get(0).text()).hasSize(200).endsWith("…");
        assertThat(request.getValue().context().get("version").asInt()).isEqualTo(1);
        ArgumentCaptor<AiMessage> stored = ArgumentCaptor.forClass(AiMessage.class);
        verify(messages).save(stored.capture());
        assertThat(stored.getValue().getContent()).endsWith("Disclaimer.");
        ArgumentCaptor<AiAnswerPush> push = ArgumentCaptor.forClass(AiAnswerPush.class);
        verify(broker).convertAndSendToUser(eq(userId.toString()), eq(AiAnswerServiceImpl.QUEUE), push.capture());
        assertThat(push.getValue().status()).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    void NSF16_theSweep_withNothingPending_failsNothing() {
        when(messages.failPendingBefore(any(), any())).thenReturn(List.of());
        assertThat(service(10, 2000).failUnanswered()).isZero();

        when(messages.failPendingBefore(any(), any())).thenReturn(null);
        assertThat(service(10, 2000).failUnanswered()).isZero();

        verifyNoInteractions(broker);
    }
}
