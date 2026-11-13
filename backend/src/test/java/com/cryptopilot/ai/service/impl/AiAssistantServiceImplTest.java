package com.cryptopilot.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.ai.dto.request.AskQuestionRequest;
import com.cryptopilot.ai.dto.request.CreateConversationRequest;
import com.cryptopilot.ai.dto.response.QuotaResponse;
import com.cryptopilot.ai.entity.AiConversation;
import com.cryptopilot.ai.model.AiContextSelection;
import com.cryptopilot.ai.model.ContextSnapshot;
import com.cryptopilot.ai.model.enums.ConversationTopic;
import com.cryptopilot.ai.repository.AiConversationRepository;
import com.cryptopilot.ai.repository.AiMessageRepository;
import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * The conversation rules of T-075 around their edges: the pair a conversation starts on, the page size of the
 * messages, the pair a question carries, an unlimited quota and each field a topic refuses on its own.
 */
class AiAssistantServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

    private final AiConversationRepository conversations = mock(AiConversationRepository.class);
    private final AiMessageRepository messages = mock(AiMessageRepository.class);
    private final ContextSnapshotBuilder snapshots = mock(ContextSnapshotBuilder.class);
    private final EntitlementApi entitlements = mock(EntitlementApi.class);
    private final MarketApi market = mock(MarketApi.class);
    private final UserLock locks = mock(UserLock.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AiAssistantServiceImpl service = new AiAssistantServiceImpl(
            conversations, messages, snapshots, entitlements, market, locks, events, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID userId = UUID.randomUUID();

    private AiConversation owned(ConversationTopic topic, UUID pairId) {
        AiConversation conversation = AiConversation.start(userId, topic, pairId, null);
        when(conversations.findByIdAndUserIdAndDeletedAtIsNull(conversation.getId(), userId))
                .thenReturn(Optional.of(conversation));
        when(conversations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messages.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshots.build(any(), any())).thenReturn(new ContextSnapshot(1, NOW, null, null, null, null, null));
        when(entitlements.getLimit(userId, Feature.AI_CHAT_DAILY)).thenReturn(100);
        return conversation;
    }

    private static AskQuestionRequest question(
            UUID pairId, MarketType market, String timeframe, UUID planId, Boolean includePerformance) {
        return new AskQuestionRequest("Câu hỏi", pairId, market, timeframe, planId, includePerformance);
    }

    @Test
    void SRS391_aConversationStartsOnAListedPairOnly() {
        UUID unlisted = UUID.randomUUID();
        when(market.pairListings(List.of(unlisted))).thenReturn(List.of());
        assertThatThrownBy(() ->
                        service.start(userId, new CreateConversationRequest(ConversationTopic.MARKET, unlisted, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(conversations, never()).save(any());

        UUID listed = UUID.randomUUID();
        when(market.pairListings(List.of(listed))).thenReturn(List.of(new PairListing(listed, "BTCUSDT", true, true)));
        when(conversations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(service.start(userId, new CreateConversationRequest(ConversationTopic.MARKET, listed, null))
                        .pairId())
                .isEqualTo(listed);
    }

    @Test
    void SRS391_theMessages_followThePageSizeAsked() {
        AiConversation conversation = owned(ConversationTopic.GENERAL, null);
        when(messages.findByConversationIdOrderByCreatedAtDesc(eq(conversation.getId()), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        service.messages(userId, conversation.getId(), 1, 10);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(messages).findByConversationIdOrderByCreatedAtDesc(eq(conversation.getId()), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void SRS391_aQuestionNamingAPair_isAskedOnThatPair_notTheConversations() {
        UUID started = UUID.randomUUID();
        UUID asked = UUID.randomUUID();
        AiConversation conversation = owned(ConversationTopic.GENERAL, started);

        service.ask(userId, conversation.getId(), question(asked, MarketType.SPOT, "4h", null, null));

        ArgumentCaptor<AiContextSelection> selection = ArgumentCaptor.forClass(AiContextSelection.class);
        verify(snapshots).build(eq(userId), selection.capture());
        assertThat(selection.getValue().pairId()).isEqualTo(asked);
    }

    @Test
    void BR50_anUnlimitedQuota_hasNoRemainingCount_andAlwaysAllowsAQuestion() {
        when(messages.countQuestionsSince(eq(userId), any())).thenReturn(250L);
        when(entitlements.getLimit(userId, Feature.AI_CHAT_DAILY)).thenReturn(null);

        QuotaResponse quota = service.quota(userId);

        assertThat(quota.limit()).isNull();
        assertThat(quota.remaining()).isNull();
        assertThat(quota.canAsk()).isTrue();
    }

    @Test
    void BR51_aMarketConversation_refusesThePerformanceAlone_andAPlanAlone() {
        AiConversation conversation = owned(ConversationTopic.MARKET, null);

        assertThatThrownBy(() -> service.ask(userId, conversation.getId(), question(null, null, null, null, true)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.errors())
                        .containsOnlyKeys("includePerformance"));
        assertThatThrownBy(() ->
                        service.ask(userId, conversation.getId(), question(null, null, null, UUID.randomUUID(), false)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.errors())
                        .containsOnlyKeys("planId"));
        verify(entitlements, never()).checkDailyQuota(any(), any(), anyLong());
    }

    @Test
    void BR51_aMyTradingConversation_refusesTheMarketAlone_andTheTimeframeAlone() {
        AiConversation conversation = owned(ConversationTopic.MY_TRADING, null);

        assertThatThrownBy(() ->
                        service.ask(userId, conversation.getId(), question(null, MarketType.SPOT, null, null, null)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.errors())
                        .containsOnlyKeys("market"));
        assertThatThrownBy(() -> service.ask(userId, conversation.getId(), question(null, null, "1h", null, null)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.errors())
                        .containsOnlyKeys("timeframe"));
    }

    @Test
    void BR51_aMyTradingConversation_takesAPlanAndThePerformance() {
        AiConversation conversation = owned(ConversationTopic.MY_TRADING, null);
        UUID planId = UUID.randomUUID();

        service.ask(userId, conversation.getId(), question(null, null, null, planId, true));

        ArgumentCaptor<AiContextSelection> selection = ArgumentCaptor.forClass(AiContextSelection.class);
        verify(snapshots).build(eq(userId), selection.capture());
        assertThat(selection.getValue().planId()).isEqualTo(planId);
        assertThat(selection.getValue().includePerformance()).isTrue();
        assertThat(selection.getValue().pairId()).isNull();
    }
}
