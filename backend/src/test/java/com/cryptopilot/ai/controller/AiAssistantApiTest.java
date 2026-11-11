package com.cryptopilot.ai.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.ai.AiServiceException;
import com.cryptopilot.ai.calculator.QuotaDay;
import com.cryptopilot.ai.client.AiServiceClient;
import com.cryptopilot.ai.model.AiChatAnswer;
import com.cryptopilot.ai.model.AiChatRequest;
import com.cryptopilot.ai.model.enums.SenderRole;
import com.cryptopilot.ai.service.AiAnswerService;
import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.journal.TradeRow;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.support.TestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The AI Assistant end to end against the real schema, billing and event registry (T-073, T-075); only the AI Service
 * is replaced.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AiAssistantApiTest {

    private static final String AI = "/api/v1/ai";
    private static final String PRO_MONTHLY = "019b76da-a800-7002-8000-000000000002";
    private static final String PREMIUM_MONTHLY = "019b76da-a800-7002-8000-000000000004";
    private static final String DISCLAIMER = "Thông tin chỉ mang tính tham khảo, không phải lời khuyên đầu tư.";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private AiAnswerService answers;

    @Autowired
    private Clock clock;

    @MockitoBean
    private AiServiceClient client;

    private final List<UUID> accounts = new ArrayList<>();
    private final List<UUID> pairs = new ArrayList<>();
    private UUID free;
    private UUID pro;

    @BeforeEach
    void setUp() {
        free = account();
        pro = account();
        subscribe(pro, PRO_MONTHLY);
    }

    @AfterEach
    void removeRows() {
        for (UUID account : accounts) {
            sql.sql("delete from trading_journal where user_id = ?")
                    .param(account)
                    .update();
            sql.sql("delete from trading_plan where user_id = ?").param(account).update();
            sql.sql("delete from ai_conversation where user_id = ?")
                    .param(account)
                    .update();
            sql.sql("""
                    delete from user_subscription where order_id in
                        (select order_id from subscription_order where user_id = ?)""").param(account).update();
            sql.sql("delete from subscription_order where user_id = ?")
                    .param(account)
                    .update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        pairs.forEach(new MarketTestData(sql, Instant.now())::remove);
    }

    @Test
    void BR50_onFree_noConversationCanBeStarted_andAnOldOneStaysReadOnly() throws Exception {
        as(
                        free,
                        post(AI + "/conversations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageCode").value("MSG29"));

        UUID old = conversationOf(free);
        as(free, get(AI + "/conversations/" + old + "/messages")).andExpect(status().isOk());
        ask(free, old, "What is RSI?")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageCode").value("MSG29"));
        as(free, get(AI + "/quota"))
                .andExpect(jsonPath("$.limit").value(0))
                .andExpect(jsonPath("$.canAsk").value(false));
        verify(client, never()).chat(any());
    }

    @Test
    void NSF16_aQuestionIsStoredPending_thenAnsweredWithTheDisclaimer_andUsesOneUnitOfQuota() throws Exception {
        when(client.chat(any())).thenReturn(new AiChatAnswer("RSI 72 là vùng quá mua.", "gemini-test", 163, 59));
        UUID conversation = start(pro);

        String asked = ask(pro, conversation, "  RSI 72 nghĩa là gì?  ")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.question.status").value("PENDING"))
                .andExpect(jsonPath("$.question.role").value("USER"))
                .andExpect(jsonPath("$.question.content").value("RSI 72 nghĩa là gì?"))
                .andExpect(jsonPath("$.quota.limit").value(30))
                .andExpect(jsonPath("$.quota.used").value(1))
                .andExpect(jsonPath("$.quota.remaining").value(29))
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID question = UUID.fromString(JsonPath.read(asked, "$.question.id"));

        awaitStatus(question, "COMPLETED");
        String page = as(pro, get(AI + "/conversations/" + conversation + "/messages"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(page, "$.items[0].role")).isEqualTo("ASSISTANT");
        assertThat((String) JsonPath.read(page, "$.items[0].content"))
                .isEqualTo("RSI 72 là vùng quá mua.\n\n" + DISCLAIMER);
        assertThat(sql.sql("select input_tokens + output_tokens from ai_message where sender_role = 'ASSISTANT'"
                                + " and conversation_id = ?")
                        .param(conversation)
                        .query(Integer.class)
                        .single())
                .isEqualTo(222);
        as(pro, get(AI + "/conversations"))
                .andExpect(jsonPath("$.items[0].id").value(conversation.toString()))
                .andExpect(jsonPath("$.items[0].title").value("RSI 72 nghĩa là gì?"));
        as(pro, get(AI + "/quota")).andExpect(jsonPath("$.used").value(1));
    }

    @Test
    void NSF16_theActiveConfigurationAndTheEarlierTurns_goWithTheQuestion() throws Exception {
        when(client.chat(any())).thenReturn(new AiChatAnswer("ok", "gemini-test", 1, 1));
        UUID conversation = start(pro);
        UUID first = questionId(ask(pro, conversation, "What is RSI?"));
        awaitStatus(first, "COMPLETED");

        UUID second = questionId(ask(pro, conversation, "And 72?"));
        awaitStatus(second, "COMPLETED");

        ArgumentCaptor<AiChatRequest> sent = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(client, org.mockito.Mockito.times(2)).chat(sent.capture());
        AiChatRequest last = sent.getAllValues().get(1);
        assertThat(last.model()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(last.systemPrompt()).contains("Không bao giờ khuyên mua, bán");
        assertThat(last.maxOutputTokens()).isEqualTo(1024);
        assertThat(last.question()).isEqualTo("And 72?");
        assertThat(last.history())
                .extracting(turn -> turn.role())
                .containsExactly(SenderRole.USER, SenderRole.ASSISTANT);
        assertThat(last.history().get(0).text()).isEqualTo("What is RSI?");
        assertThat(last.context()).isNull();
    }

    @Test
    void BR50_aFailedQuestion_usesNoQuota_andIsLeftOutOfTheHistory() throws Exception {
        when(client.chat(any())).thenThrow(new AiServiceException("LLM_UNAVAILABLE", true, "down"));
        UUID conversation = start(pro);

        UUID question = questionId(ask(pro, conversation, "What is RSI?"));

        awaitStatus(question, "FAILED");
        as(pro, get(AI + "/quota"))
                .andExpect(jsonPath("$.used").value(0))
                .andExpect(jsonPath("$.remaining").value(30));
        assertThat(count("select count(*) from ai_message where conversation_id = ?", conversation))
                .isEqualTo(1);
    }

    @Test
    void BR50_withTodaysQuotaUsedUp_theNextQuestionIsRefusedWithMsg30_andDeletingDoesNotGiveItBack() throws Exception {
        UUID used = start(pro);
        Instant today = QuotaDay.start(clock.instant()).plusSeconds(1);
        for (int i = 0; i < 30; i++) {
            message(used, "USER", "COMPLETED", today);
        }
        as(pro, delete(AI + "/conversations/" + used)).andExpect(status().isNoContent());
        UUID fresh = start(pro);

        ask(pro, fresh, "One more?")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.messageCode").value("MSG30"));
        as(pro, get(AI + "/quota"))
                .andExpect(jsonPath("$.used").value(30))
                .andExpect(jsonPath("$.canAsk").value(false));
        verify(client, never()).chat(any());
    }

    @Test
    void BR50_yesterdaysQuestions_doNotCount() throws Exception {
        UUID conversation = start(pro);
        message(conversation, "USER", "COMPLETED", Instant.now().minus(2, ChronoUnit.DAYS));

        as(pro, get(AI + "/quota")).andExpect(jsonPath("$.used").value(0));
    }

    @Test
    void BR51_thePerformanceSummary_isRefusedBelowPremium() throws Exception {
        UUID conversation = start(pro);

        as(
                        pro,
                        post(AI + "/conversations/" + conversation + "/messages")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"Review my journal\",\"includePerformance\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageCode").value("MSG29"));

        UUID premium = account();
        subscribe(premium, PREMIUM_MONTHLY);
        when(client.chat(any())).thenReturn(new AiChatAnswer("ok", "gemini-test", 1, 1));
        UUID mine = start(premium);
        UUID question = questionId(as(
                        premium,
                        post(AI + "/conversations/" + mine + "/messages")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"Review my journal\",\"includePerformance\":true}"))
                .andExpect(status().isAccepted()));
        awaitStatus(question, "COMPLETED");
        String snapshot = sql.sql("select context_snapshot::text from ai_message where message_id = ?")
                .param(question)
                .query(String.class)
                .single();
        JsonNode performance = JsonMapper.builder().build().readTree(snapshot).path("performance");
        assertThat(performance.path("available").asBoolean()).isFalse();
        assertThat(performance.path("message").asString()).contains("no closed simulated trade");
        assertThat(performance.path("summary").isMissingNode()
                        || performance.path("summary").isNull())
                .isTrue();
        assertThat(snapshot).doesNotContain(premium.toString());
        assertThat(snapshot).doesNotContain("@ai-assistant.invalid");
    }

    @Test
    void BR51_onPremium_theSnapshotCarriesTheJournalsAggregates_andNothingOfTheTrades() throws Exception {
        UUID premium = account();
        subscribe(premium, PREMIUM_MONTHLY);
        UUID pair = new MarketTestData(sql, Instant.now())
                .pair(
                        "AI" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT",
                        true,
                        true,
                        "TRADING",
                        "TRADING",
                        0);
        pairs.add(pair);
        UUID plan = plan(premium, pair);
        TradeRow.closed(premium, pair, Instant.now().minus(1, ChronoUnit.DAYS))
                .simulated(plan)
                .stop("90")
                .amounts("20", "1")
                .deviation("0.1")
                .excursions("95", "125")
                .review("my secret setup", "my lesson", "my mistake", null)
                .insert(sql);
        when(client.chat(any())).thenReturn(new AiChatAnswer("ok", "gemini-test", 1, 1));
        UUID conversation = start(premium, "MY_TRADING");

        UUID question = questionId(as(
                        premium,
                        post(AI + "/conversations/" + conversation + "/messages")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"Review my journal\",\"includePerformance\":true}"))
                .andExpect(status().isAccepted()));

        awaitStatus(question, "COMPLETED");
        String snapshot = sql.sql("select context_snapshot::text from ai_message where message_id = ?")
                .param(question)
                .query(String.class)
                .single();
        JsonNode root = JsonMapper.builder().build().readTree(snapshot);
        assertThat(root.path("version").asInt()).isEqualTo(3);
        JsonNode summary = root.path("performance").path("summary");
        assertThat(root.path("performance").path("available").asBoolean()).isTrue();
        assertThat(summary.path("trades").asInt()).isEqualTo(1);
        assertThat(summary.path("wins").asInt()).isEqualTo(1);
        assertThat(new java.math.BigDecimal(summary.path("totalNetPnl").asString()))
                .isEqualByComparingTo("19");
        assertThat(new java.math.BigDecimal(summary.path("averageRealizedR").asString()))
                .isEqualByComparingTo("1.9");
        assertThat(new java.math.BigDecimal(
                        summary.path("averageEntryDeviationR").asString()))
                .isEqualByComparingTo("0.1");
        assertThat(new java.math.BigDecimal(summary.path("averageMaeR").asString()))
                .isEqualByComparingTo("0.5");
        assertThat(new java.math.BigDecimal(summary.path("averageMfeR").asString()))
                .isEqualByComparingTo("2.5");
        assertThat(summary.propertyNames())
                .containsExactlyInAnyOrder(
                        "trades",
                        "wins",
                        "losses",
                        "breakeven",
                        "winRate",
                        "totalNetPnl",
                        "totalFees",
                        "totalFunding",
                        "provisionalTrades",
                        "profitFactor",
                        "averageRealizedR",
                        "tradesWithR",
                        "averageEntryDeviationR",
                        "costOfLateEntryR",
                        "averageMaeR",
                        "averageMfeR");
        assertThat(snapshot)
                .doesNotContain(premium.toString())
                .doesNotContain(pair.toString())
                .doesNotContain(plan.toString())
                .doesNotContain("my secret setup")
                .doesNotContain("my lesson");
    }

    @Test
    void SRS391_aConversationIsTheTradersOwn_renamedAndDeleted_andAnotherTradersIsNotFound() throws Exception {
        UUID conversation = start(pro);
        UUID other = account();
        subscribe(other, PRO_MONTHLY);

        as(other, get(AI + "/conversations/" + conversation + "/messages")).andExpect(status().isNotFound());
        ask(other, conversation, "Hi").andExpect(status().isNotFound());

        as(
                        pro,
                        patch(AI + "/conversations/" + conversation)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"  BTC   setup  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("BTC setup"));
        as(
                        pro,
                        patch(AI + "/conversations/" + conversation)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\" \"}"))
                .andExpect(status().isBadRequest());

        as(pro, delete(AI + "/conversations/" + conversation)).andExpect(status().isNoContent());
        as(pro, get(AI + "/conversations")).andExpect(jsonPath("$.total").value(0));
        as(pro, get(AI + "/conversations/" + conversation + "/messages")).andExpect(status().isNotFound());
    }

    @Test
    void SRS391_eachTabOfScr29_listsOnlyTheConversationsOfItsTopic() throws Exception {
        UUID market = start(pro, "MARKET");
        UUID mine = start(pro, "MY_TRADING");
        UUID general = start(pro);

        as(pro, get(AI + "/conversations").param("topic", "MARKET"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(market.toString()))
                .andExpect(jsonPath("$.items[0].topic").value("MARKET"));
        as(pro, get(AI + "/conversations").param("topic", "MY_TRADING"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(mine.toString()));
        as(pro, get(AI + "/conversations").param("topic", "GENERAL"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(general.toString()));
        as(pro, get(AI + "/conversations")).andExpect(jsonPath("$.total").value(3));
        as(pro, get(AI + "/conversations").param("topic", "OTHER")).andExpect(status().isBadRequest());
    }

    @Test
    void BR51_aMarketConversation_refusesAPlanAndThePerformanceSummary() throws Exception {
        UUID conversation = start(pro, "MARKET");

        as(
                        pro,
                        post(AI + "/conversations/" + conversation + "/messages")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"Review my plan\",\"planId\":\"" + UUID.randomUUID()
                                        + "\",\"includePerformance\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.planId").value("MSG01"))
                .andExpect(jsonPath("$.errors.includePerformance").value("MSG01"));
        assertThat(count("select count(*) from ai_message where conversation_id = ?", conversation))
                .isZero();
        verify(client, never()).chat(any());
    }

    @Test
    void BR51_aMyTradingConversation_isNotAboutAPairOfItsOwn() throws Exception {
        as(
                        pro,
                        post(AI + "/conversations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"topic\":\"MY_TRADING\",\"pairId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pairId").value("MSG01"));

        UUID conversation = start(pro, "MY_TRADING");
        as(
                        pro,
                        post(AI + "/conversations/" + conversation + "/messages")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"How is BTC?\",\"pairId\":\"" + UUID.randomUUID()
                                        + "\",\"market\":\"SPOT\",\"timeframe\":\"4h\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pairId").value("MSG01"))
                .andExpect(jsonPath("$.errors.market").value("MSG01"))
                .andExpect(jsonPath("$.errors.timeframe").value("MSG01"));
        verify(client, never()).chat(any());
    }

    @Test
    void SRS391_aQuestionLongerThan1000Characters_isRefused() throws Exception {
        UUID conversation = start(pro);

        ask(pro, conversation, "x".repeat(1001)).andExpect(status().isBadRequest());
        ask(pro, conversation, " ").andExpect(status().isBadRequest());
        verify(client, never()).chat(any());
    }

    @Test
    void SRS391_aQuestionLeftPendingPastTheTimeout_isFailedBySweep() throws Exception {
        UUID conversation = start(pro);
        UUID stale = message(conversation, "USER", "PENDING", Instant.now().minusSeconds(120));
        UUID recent = message(conversation, "USER", "PENDING", Instant.now().minusSeconds(5));

        answers.failUnanswered();

        assertThat(statusOf(stale)).isEqualTo("FAILED");
        assertThat(statusOf(recent)).isEqualTo("PENDING");
    }

    @Test
    void NSF16_anAnswerArrivingAfterTheSweep_isDropped() throws Exception {
        UUID conversation = start(pro);
        UUID question = message(conversation, "USER", "FAILED", Instant.now().minusSeconds(90));

        answers.answer(question);

        verify(client, never()).chat(any());
        assertThat(count("select count(*) from ai_message where conversation_id = ?", conversation))
                .isEqualTo(1);
    }

    @Test
    void NSF16_anAnswerThatArrivesLate_stillFollowsItsOwnQuestionInTheHistory() throws Exception {
        CountDownLatch firstMayAnswer = new CountDownLatch(1);
        when(client.chat(any())).thenAnswer(call -> {
            String asked = call.<AiChatRequest>getArgument(0).question();
            if (asked.equals("q1")) {
                assertThat(firstMayAnswer.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return new AiChatAnswer("a" + asked.substring(1), "gemini-test", 1, 1);
        });
        UUID conversation = start(pro);

        UUID first = questionId(ask(pro, conversation, "q1"));
        UUID second = questionId(ask(pro, conversation, "q2"));
        awaitStatus(second, "COMPLETED");
        firstMayAnswer.countDown();
        awaitStatus(first, "COMPLETED");
        UUID third = questionId(ask(pro, conversation, "q3"));
        awaitStatus(third, "COMPLETED");

        ArgumentCaptor<AiChatRequest> sent = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(client, org.mockito.Mockito.times(3)).chat(sent.capture());
        AiChatRequest last = sent.getAllValues().stream()
                .filter(request -> request.question().equals("q3"))
                .findFirst()
                .orElseThrow();
        assertThat(last.history())
                .extracting(turn -> turn.text().substring(0, 2))
                .containsExactly("q1", "a1", "q2", "a2");
    }

    private UUID plan(UUID owner, UUID pair) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.now().minus(3, ChronoUnit.DAYS));
        sql.sql("""
                        insert into trading_plan (plan_id, user_id, pair_id, market_type, direction, entry_type,
                                                  entry_price, stop_loss_price, take_profit_price, capital_amount,
                                                  risk_percent, plan_status, analysis_timeframe, created_at, updated_at)
                        values (?, ?, ?, 'SPOT', 'LONG', 'LIMIT', 100, 90, 110, 1000, 1, 'DRAFT', '1h', ?, ?)""").params(id, owner, pair, at, at).update();
        return id;
    }

    private UUID start(UUID user) throws Exception {
        return start(user, null);
    }

    private UUID start(UUID user, String topic) throws Exception {
        String body = as(
                        user,
                        post(AI + "/conversations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(topic == null ? "{}" : "{\"topic\":\"" + topic + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.topic").value(topic == null ? "GENERAL" : topic))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions ask(UUID user, UUID conversation, String question) throws Exception {
        String payload = "{\"question\":" + quoted(question) + "}";
        return as(
                user,
                post(AI + "/conversations/" + conversation + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload));
    }

    private static UUID questionId(ResultActions asked) throws Exception {
        return UUID.fromString(JsonPath.read(asked.andReturn().getResponse().getContentAsString(), "$.question.id"));
    }

    private void awaitStatus(UUID question, String expected) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(statusOf(question))
                .as("question %s", question)
                .isEqualTo(expected));
    }

    private String statusOf(UUID message) {
        return sql.sql("select message_status from ai_message where message_id = ?")
                .param(message)
                .query(String.class)
                .single();
    }

    private long count(String query, UUID id) {
        return sql.sql(query).param(id).query(Long.class).single();
    }

    private UUID conversationOf(UUID user) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into ai_conversation (conversation_id, user_id, created_at, updated_at, version)
                        values (?, ?, ?, ?, 0)""").params(id, user, at, at).update();
        return id;
    }

    private UUID message(UUID conversation, String role, String status, Instant at) {
        UUID id = UUID.randomUUID();
        Timestamp when = Timestamp.from(at);
        sql.sql("""
                        insert into ai_message (message_id, conversation_id, sender_role, content, message_status,
                                                created_at, updated_at, version)
                        values (?, ?, ?, 'q', ?, ?, ?, 0)""").params(id, conversation, role, status, when, when).update();
        return id;
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@ai-assistant.invalid", at, at).update();
        accounts.add(id);
        return id;
    }

    private void subscribe(UUID user, String packageId) {
        UUID order = UUID.randomUUID();
        Instant now = Instant.now();
        sql.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, created_at, paid_at,
                                                        expires_at, updated_at, version)
                        values (?, ?, ?, ?, 99000, 'VND', 'VNPAY', 'PAID', ?, ?, ?, ?, 0)""")
                .params(
                        order,
                        user,
                        UUID.fromString(packageId),
                        "AI-" + order,
                        Timestamp.from(now),
                        Timestamp.from(now),
                        Timestamp.from(now.plusSeconds(900)),
                        Timestamp.from(now))
                .update();
        sql.sql("""
                        insert into user_subscription (subscription_id, order_id, start_at, end_at,
                                                       subscription_status, created_at, updated_at, version)
                        values (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)""")
                .params(
                        UUID.randomUUID(),
                        order,
                        Timestamp.from(now.minusSeconds(60)),
                        Timestamp.from(now.plus(30, ChronoUnit.DAYS)),
                        Timestamp.from(now),
                        Timestamp.from(now))
                .update();
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(user)));
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String bearer(UUID userId) {
        Instant issued = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(issued)
                .expiresAt(issued.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, "TRADER")
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }
}
