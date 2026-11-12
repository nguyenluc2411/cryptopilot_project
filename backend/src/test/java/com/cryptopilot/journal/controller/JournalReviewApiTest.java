package com.cryptopilot.journal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.journal.client.ChartImageStorage;
import com.cryptopilot.journal.client.ChartImageStorageException;
import com.cryptopilot.journal.client.ChartImageUploadTicket;
import com.cryptopilot.journal.client.StoredChartImage;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/**
 * SCR-21 review over HTTP and the real schema: the owner replaces strategy, texts and chart image of a SIMULATED or
 * MANUAL trade; execution facts are never changed (BR-40), neither is the version an open position's close compares;
 * another Trader is answered "not found". The media storage is a mock standing for Cloudinary.
 *
 * <p>Rule: BR-40; UC-23; SRS 3.6.1; MSG01, MSG14, MSG15, MSG32, MSG41.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class JournalReviewApiTest {

    private static final String JOURNAL = "/api/v1/journal/";
    private static final Instant T0 = Instant.parse("2026-11-10T08:00:00Z");
    /** The BREAKOUT strategy V3 seeded. */
    private static final UUID BREAKOUT = UUID.fromString("019b76da-a800-7001-8000-000000000001");

    private static final long FIVE_MB = 5L * 1024 * 1024;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private ChartImageStorage images;

    private final List<UUID> accounts = new ArrayList<>();
    private UUID pair;
    private UUID trader;

    @BeforeEach
    void insertRows() {
        String symbol = "JR" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, Instant.now()).pair(symbol, true, true, "TRADING", "TRADING", 0);
        trader = account();
    }

    @AfterEach
    void removeRows() {
        for (UUID account : accounts) {
            sql.sql("delete from trading_journal where user_id = ?")
                    .param(account)
                    .update();
            sql.sql("delete from trading_plan where user_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        new MarketTestData(sql, Instant.now()).remove(pair);
    }

    /** BR-40: the review is saved and answered as SCR-21 shows it; the facts and the version stay as they were. */
    @Test
    void BR40_aReview_isSaved_andLeavesTheFactsAndTheVersionAlone() throws Exception {
        UUID trade = simulated(trader);
        Map<String, Object> before = row(trade);

        review(trader, trade, """
                        {"strategyId": "%s", "setupDescription": "Range break on volume",
                         "lessonsLearned": "Wait for the retest", "mistakes": "Moved the stop"}
                        """.formatted(BREAKOUT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(trade.toString()))
                .andExpect(jsonPath("$.strategyId").value(BREAKOUT.toString()))
                .andExpect(jsonPath("$.strategyName").value("Breakout"))
                .andExpect(jsonPath("$.setupDescription").value("Range break on volume"))
                .andExpect(jsonPath("$.lessonsLearned").value("Wait for the retest"))
                .andExpect(jsonPath("$.mistakes").value("Moved the stop"))
                .andExpect(jsonPath("$.chartImageUrl").doesNotExist())
                .andExpect(jsonPath("$.entryPrice").value("100.000000000000"));

        Map<String, Object> after = row(trade);
        for (String fact : List.of(
                "entry_price",
                "quantity",
                "entry_time",
                "exit_price",
                "exit_time",
                "realized_pnl",
                "fee_amount",
                "version")) {
            assertThat(after.get(fact)).as(fact).isEqualTo(before.get(fact));
        }
    }

    /** A field left out of the body is cleared, as the whole review is replaced. */
    @Test
    void BR40_aReview_replacesTheStoredOne() throws Exception {
        UUID trade = simulated(trader);
        review(trader, trade, "{\"setupDescription\": \"first\", \"mistakes\": \"late\"}");

        review(trader, trade, "{\"setupDescription\": \"second\", \"lessonsLearned\": \"  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupDescription").value("second"))
                .andExpect(jsonPath("$.lessonsLearned").doesNotExist())
                .andExpect(jsonPath("$.mistakes").doesNotExist());
    }

    /** BR-40: a body naming an execution fact is refused under that field, and nothing is saved. */
    @Test
    void BR40_anExecutionFact_isRefused_andNothingIsSaved() throws Exception {
        UUID trade = simulated(trader);

        review(trader, trade, "{\"setupDescription\": \"x\", \"entryPrice\": 90, \"realizedPnl\": 100}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"))
                .andExpect(jsonPath("$.errors.entryPrice").value("MSG01"))
                .andExpect(jsonPath("$.errors.realizedPnl").value("MSG01"));

        assertThat(row(trade).get("setup_description")).isNull();
        assertThat(row(trade).get("entry_price").toString()).isEqualTo("100.000000000000");
    }

    /** SRS 3.6.1: each text holds up to 4,000 characters; one more is refused with MSG15. */
    @Test
    void BR40_aTextOf4000Characters_isSaved_and4001_isRefused() throws Exception {
        UUID trade = simulated(trader);

        review(trader, trade, "{\"lessonsLearned\": \"%s\"}".formatted("a".repeat(4000)))
                .andExpect(status().isOk());
        review(trader, trade, "{\"mistakes\": \"%s\"}".formatted("a".repeat(4001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.mistakes").value("MSG15"));
    }

    @Test
    void BR40_anUnknownStrategy_isRefused() throws Exception {
        review(trader, simulated(trader), "{\"strategyId\": \"%s\"}".formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.strategyId").value("MSG15"));
    }

    /** MSG41: another Trader's trade is not found, for the review and the upload form alike, and stays unchanged. */
    @Test
    void BR40_anotherTradersTrade_isNotFound() throws Exception {
        UUID trade = simulated(trader);
        UUID other = account();

        review(other, trade, "{\"setupDescription\": \"mine now\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));
        sign(other, trade).andExpect(status().isNotFound());

        assertThat(row(trade).get("setup_description")).isNull();
        verify(images, never()).sign(any());
    }

    /** BR-41: a manual trade is reviewed the same way. */
    @Test
    void BR40_aManualTrade_isReviewedTheSameWay() throws Exception {
        review(trader, manual(trader), "{\"setupDescription\": \"outside trade\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.setupDescription").value("outside trade"));
    }

    /** SRS 3.6.1: a PNG of exactly 5 MB signed for the owner is kept with its address. */
    @Test
    void BR40_aChartImage_signedForTheOwner_isKept() throws Exception {
        UUID trade = simulated(trader);
        String key = stored("png", FIVE_MB);

        review(trader, trade, "{\"chartImagePublicId\": \"%s\"}".formatted(key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chartImageUrl").value("https://img.example/" + key + ".png"));

        assertThat(row(trade).get("chart_image_public_id")).isEqualTo(key);
        verify(images, never()).destroy(any());
    }

    /** Saving again with the stored key keeps the image without reading the storage; a new one replaces it. */
    @Test
    void BR40_aReplacedOrRemovedImage_isDeletedFromTheStorage() throws Exception {
        UUID trade = simulated(trader);
        String first = stored("webp", 1000);
        review(trader, trade, "{\"chartImagePublicId\": \"%s\"}".formatted(first));

        review(trader, trade, "{\"chartImagePublicId\": \"%s\", \"mistakes\": \"m\"}".formatted(first))
                .andExpect(jsonPath("$.chartImageUrl").value("https://img.example/" + first + ".png"));
        verify(images, never()).destroy(any());

        String second = stored("jpg", 1000);
        review(trader, trade, "{\"chartImagePublicId\": \"%s\"}".formatted(second))
                .andExpect(status().isOk());
        verify(images).destroy(first);

        review(trader, trade, "{}").andExpect(jsonPath("$.chartImageUrl").doesNotExist());
        verify(images).destroy(second);
        assertThat(row(trade).get("chart_image_public_id")).isNull();
    }

    /** A delete the storage fails is only logged: the review is saved. */
    @Test
    void BR40_aFailedDeleteOfTheOldImage_doesNotUndoTheReview() throws Exception {
        UUID trade = simulated(trader);
        String first = stored("png", 1000);
        review(trader, trade, "{\"chartImagePublicId\": \"%s\"}".formatted(first));
        doThrow(new ChartImageStorageException("down")).when(images).destroy(first);

        review(trader, trade, "{\"setupDescription\": \"no image\"}").andExpect(status().isOk());

        assertThat(row(trade).get("chart_image_url")).isNull();
    }

    /** MSG32: a GIF, a file over 5 MB, a key not signed for the caller and a key with nothing stored are refused. */
    @Test
    void BR40_anImageOfAnotherFormatOrSize_orNotTheCallers_isRefused() throws Exception {
        UUID trade = simulated(trader);
        String gif = stored("gif", 1000);
        String large = stored("png", FIVE_MB + 1);
        String missing = "cryptopilot/journal/" + trader + "/" + UUID.randomUUID();
        when(images.issuedTo(trader, missing)).thenReturn(true);
        when(images.describe(missing)).thenReturn(Optional.empty());

        for (String key : List.of(gif, large, missing, "cryptopilot/posts/someone/else")) {
            review(trader, trade, "{\"chartImagePublicId\": \"%s\"}".formatted(key))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("MSG32"))
                    .andExpect(jsonPath("$.errors.chartImagePublicId").value("MSG32"));
        }
        assertThat(row(trade).get("chart_image_url")).isNull();
    }

    @Test
    void BR40_aStorageThatCannotCheckTheImage_answers503() throws Exception {
        String key = "cryptopilot/journal/" + trader + "/" + UUID.randomUUID();
        when(images.issuedTo(trader, key)).thenReturn(true);
        when(images.describe(key)).thenThrow(new ChartImageStorageException("down"));

        review(trader, simulated(trader), "{\"chartImagePublicId\": \"%s\"}".formatted(key))
                .andExpect(status().isServiceUnavailable());
    }

    /** The upload form is the storage's, with the size limit of MAX_IMAGE_SIZE_MB. */
    @Test
    void BR40_theUploadForm_isSignedForTheOwner_withTheSizeLimit() throws Exception {
        UUID trade = simulated(trader);
        when(images.sign(trader))
                .thenReturn(new ChartImageUploadTicket(
                        "https://api.example/upload", "key", 42, "sig", "cryptopilot/journal/x", List.of("png")));

        sign(trader, trade)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl").value("https://api.example/upload"))
                .andExpect(jsonPath("$.publicId").value("cryptopilot/journal/x"))
                .andExpect(jsonPath("$.allowedFormats[0]").value("png"))
                .andExpect(jsonPath("$.maxBytes").value(FIVE_MB));
    }

    @Test
    void BR40_withoutAStorage_theUploadForm_answers503() throws Exception {
        when(images.sign(trader)).thenThrow(new BusinessException(ErrorCode.MEDIA_STORAGE_UNAVAILABLE, "none"));

        sign(trader, simulated(trader)).andExpect(status().isServiceUnavailable());
    }

    /** A key signed for the trader and stored with the given format and size; its address ends with .png. */
    private String stored(String format, long bytes) {
        String key = "cryptopilot/journal/" + trader + "/" + UUID.randomUUID();
        when(images.issuedTo(trader, key)).thenReturn(true);
        when(images.describe(key))
                .thenReturn(
                        Optional.of(new StoredChartImage(key, format, bytes, "https://img.example/" + key + ".png")));
        return key;
    }

    private ResultActions review(UUID user, UUID trade, String body) throws Exception {
        return mvc.perform(put(JOURNAL + trade + "/review")
                .header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions sign(UUID user, UUID trade) throws Exception {
        return mvc.perform(post(JOURNAL + trade + "/chart-image/upload-signature")
                .header(HttpHeaders.AUTHORIZATION, bearer(user)));
    }

    private Map<String, Object> row(UUID id) {
        return sql.sql("select * from trading_journal where journal_id = ?")
                .param(id)
                .query()
                .singleRow();
    }

    /** A CLOSED simulated Spot LONG of 1 at 100, out at 110 by its take profit. */
    private UUID simulated(UUID owner) {
        UUID plan = UUID.randomUUID();
        sql.sql("""
                        insert into trading_plan (plan_id, user_id, pair_id, market_type, direction, entry_type,
                                                  entry_price, stop_loss_price, take_profit_price, capital_amount,
                                                  risk_percent, plan_status, analysis_timeframe, created_at, updated_at)
                        values (?, ?, ?, 'SPOT', 'LONG', 'LIMIT', 100, 90, 110, 1000, 1, 'DRAFT', '1h', ?, ?)""").params(plan, owner, pair, ts(T0), ts(T0)).update();
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into trading_journal (journal_id, user_id, pair_id, plan_id, market_type, direction,
                                                     entry_price, entry_time, quantity, source, trade_status,
                                                     exit_price, exit_time, close_reason, realized_pnl, fee_amount,
                                                     created_at, updated_at, version)
                        values (?, ?, ?, ?, 'SPOT', 'LONG', 100, ?, 1, 'SIMULATED', 'CLOSED', 110, ?, 'TAKE_PROFIT',
                                10, 0.21, ?, ?, 3)""")
                .params(id, owner, pair, plan, ts(T0), ts(T0.plusSeconds(3600)), ts(T0), ts(T0))
                .update();
        return id;
    }

    private UUID manual(UUID owner) {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into trading_journal (journal_id, user_id, pair_id, market_type, direction, entry_price,
                                                     entry_time, quantity, source, trade_status, created_at,
                                                     updated_at, version)
                        values (?, ?, ?, 'SPOT', 'LONG', 100, ?, 1, 'MANUAL', 'OPEN', ?, ?, 0)""").params(id, owner, pair, ts(T0), ts(T0), ts(T0)).update();
        return id;
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@review.invalid", ts(T0), ts(T0)).update();
        accounts.add(id);
        return id;
    }

    private String bearer(UUID userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, UserRole.TRADER.name())
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
