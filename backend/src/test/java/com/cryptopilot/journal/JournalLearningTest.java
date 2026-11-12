package com.cryptopilot.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.ClosedCandleIndicators;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.PositionExcursion;
import com.cryptopilot.trading.SimulatedFillLearning;
import com.cryptopilot.trading.SimulatedTradeClosing;
import com.cryptopilot.trading.SimulatedTradeOpening;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.model.enums.PositionStatus;
import com.cryptopilot.user.model.enums.UserRole;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The learning data of a fill against the real schema: stored with the OPEN record and only once for a plan, the
 * extremes widened monotonically while open and at the close, shown on SCR-21 with the R figures derived from it, and
 * read by other modules through {@link FillLearningReader}. Each test commits its own rows; {@link #removeRows()}
 * deletes them.
 *
 * <p>Rule: BR-34, BR-41, BR-70; D-111 (M4), D-112 R5.
 *
 * <p>Reference: Helland, P. (2012). Idempotence is not a medical condition. <i>ACM Queue</i>, 10(4).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class JournalLearningTest {

    private static final Instant FILLED_AT = Instant.parse("2026-11-12T08:01:00Z");
    private static final Instant CANDLE = Instant.parse("2026-11-12T07:00:00Z");

    @Autowired
    private TradeJournal journal;

    @Autowired
    private FillLearningReader reader;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private PlatformTransactionManager transactions;

    private final List<UUID> accounts = new ArrayList<>();
    private UUID pair;
    private UUID trader;

    @BeforeEach
    void insertRows() {
        String symbol = "JL" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
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

    /** BR-70: every value of the fill's learning data is stored with its record and shown on SCR-21. */
    @Test
    void BR70_theLearningDataOfAFill_isStoredWithItsRecord_andShownInTheDetail() throws Exception {
        OpenTrade opened = open(plan(trader), learning("70.00", "55.00", true));

        detail(trader, opened.positionId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("SIMULATED"))
                .andExpect(jsonPath("$.learning.plannedEntryPrice").value("100.000000000000"))
                .andExpect(jsonPath("$.learning.entryDeviationR").value("0.20000000"))
                .andExpect(jsonPath("$.learning.entryLiquidity").value("TAKER"))
                .andExpect(jsonPath("$.learning.riskAmount").value("10.00000000"))
                .andExpect(jsonPath("$.learning.analysisTimeframe").value("1h"))
                .andExpect(jsonPath("$.learning.setupScorePreset").value("SWING v1"))
                .andExpect(jsonPath("$.learning.setupScoreAtPlan").value("70.00"))
                .andExpect(jsonPath("$.learning.setupScoreAtFill").value("55.00"))
                .andExpect(jsonPath("$.learning.setupScoreDropWarned").value(true))
                .andExpect(jsonPath("$.learning.indicators.timeframe").value("1h"))
                .andExpect(jsonPath("$.learning.indicators.openTime").value("2026-11-12T07:00:00Z"))
                .andExpect(jsonPath("$.learning.indicators.rsi14").value("48.25"))
                .andExpect(jsonPath("$.learning.indicators.ema200").value("90.1234567891"))
                .andExpect(jsonPath("$.learning.indicators.macdHistogram").value("-0.04"))
                .andExpect(jsonPath("$.learning.maxAdversePrice").value("101.000000000000"))
                .andExpect(jsonPath("$.learning.maxFavorablePrice").value("101.000000000000"))
                .andExpect(jsonPath("$.learning.rMultiple").doesNotExist());
    }

    /** BR-34, BR-70: a replayed fill of the same plan is refused, so its learning data is stored once. */
    @Test
    void BR70_aReplayedFill_storesItsLearningDataOnce() {
        UUID plan = plan(trader);
        OpenTrade first = open(plan, learning("70.00", "55.00", true));

        assertThatThrownBy(() -> open(plan, learning("80.00", "79.00", false)))
                .isInstanceOf(DataIntegrityViolationException.class);

        Map<String, Object> row = row(first.positionId());
        assertThat(sql.sql("select count(*) from trading_journal where plan_id = ?")
                        .param(plan)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat((BigDecimal) row.get("setup_score_at_plan")).isEqualByComparingTo("70");
        assertThat(row.get("setup_score_drop_warned")).isEqualTo(true);
    }

    /** D-112: when a score is unknown nothing was compared, so the flag is null, not false. */
    @Test
    void BR70_anUnknownScore_storesNoFlag() throws Exception {
        OpenTrade opened = open(plan(trader), learning("70.00", null, null));

        assertThat(row(opened.positionId()).get("setup_score_drop_warned")).isNull();
        detail(trader, opened.positionId())
                .andExpect(jsonPath("$.learning.setupScoreAtFill").doesNotExist())
                .andExpect(jsonPath("$.learning.setupScoreDropWarned").doesNotExist());
    }

    /** BR-70: the extremes of an open LONG only ever widen, and the row's version, read by every close, stays. */
    @Test
    void BR70_theExtremesOfAnOpenLong_onlyWiden_andKeepTheVersion() {
        OpenTrade opened = open(plan(trader), learning("70.00", "55.00", true));
        UUID id = opened.positionId();

        widen(id, "97", "105");
        assertExtremes(id, "97", "105");
        widen(id, "98", "104");
        assertExtremes(id, "97", "105");
        widen(id, "96", "104");
        assertExtremes(id, "96", "105");
        assertThat(((Number) row(id).get("version")).longValue()).isEqualTo(opened.version());
    }

    /** BR-70: for a SHORT the adverse extreme is the highest price and the favourable one the lowest. */
    @Test
    void BR70_theExtremesOfAnOpenShort_areMirrored() {
        OpenTrade opened = open(plan(trader), Direction.SHORT, learning("70.00", "55.00", true));
        UUID id = opened.positionId();

        widen(id, "104", "95");
        widen(id, "103", "96");

        assertExtremes(id, "104", "95");
    }

    /**
     * BR-70: the close keeps the wider of the stored extremes and those up to the exit; the detail then derives R from
     * them: net (24 − 0.2) / 10 = 2.38 R, MAE (101 − 95) × 2 / 10 = 1.2 R, MFE (113 − 101) × 2 / 10 = 2.4 R.
     */
    @Test
    void BR70_theClose_keepsTheWiderExtremes_andTheDetailDerivesR() throws Exception {
        OpenTrade opened = open(plan(trader), learning("70.00", "55.00", true));
        widen(opened.positionId(), "95", "110");

        close(opened, "97", "113");

        assertExtremes(opened.positionId(), "95", "113");
        detail(trader, opened.positionId())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.learning.rMultiple").value("2.38000000"))
                .andExpect(jsonPath("$.learning.maeR").value("1.20000000"))
                .andExpect(jsonPath("$.learning.mfeR").value("2.40000000"));
    }

    /** BR-41: a manual trade has no fill the system saw, so neither a learning block nor a reading. */
    @Test
    void BR41_aManualTrade_hasNoLearningData() throws Exception {
        UUID manual = UUID.randomUUID();
        sql.sql("""
                        insert into trading_journal (journal_id, user_id, pair_id, market_type, direction, entry_price,
                                                     entry_time, quantity, source, trade_status, created_at,
                                                     updated_at, version)
                        values (?, ?, ?, 'SPOT', 'LONG', 100, ?, 1, 'MANUAL', 'OPEN', ?, ?, 0)""")
                .params(manual, trader, pair, ts(FILLED_AT), ts(FILLED_AT), ts(FILLED_AT))
                .update();

        detail(trader, manual)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.learning").doesNotExist());
        assertThat(reader.ofTrade(trader, manual)).isEmpty();
    }

    /** BR-70: the reader gives the Trader's own simulated trades only, the latest closed first. */
    @Test
    void BR70_theReader_givesTheTradersOwnTrades_theLatestClosedFirst() {
        OpenTrade older = open(plan(trader), learning("70.00", "55.00", true));
        OpenTrade newer = open(plan(trader), learning("60.00", "58.00", false));
        OpenTrade stillOpen = open(plan(trader), learning("60.00", "58.00", false));
        close(older, "99", "105");
        close(newer, "99", "105", FILLED_AT.plusSeconds(7200));
        UUID other = account();

        assertThat(reader.latestClosed(trader, 10))
                .extracting(FillLearning::journalId)
                .containsExactly(newer.positionId(), older.positionId());
        assertThat(reader.ofTrade(trader, stillOpen.positionId())).hasValueSatisfying(read -> {
            assertThat(read.status()).isEqualTo(PositionStatus.OPEN);
            assertThat(read.rMultiple()).isNull();
            assertThat(read.indicators().rsi14()).isEqualByComparingTo("48.25");
        });
        assertThat(reader.ofTrade(other, older.positionId())).isEmpty();
        assertThat(reader.latestClosed(other, 10)).isEmpty();
    }

    private OpenTrade open(UUID plan, SimulatedFillLearning learning) {
        return open(plan, Direction.LONG, learning);
    }

    private OpenTrade open(UUID plan, Direction direction, SimulatedFillLearning learning) {
        return new TransactionTemplate(transactions)
                .execute(status -> journal.openSimulatedTrade(new SimulatedTradeOpening(
                        plan,
                        trader,
                        pair,
                        // Spot is LONG only; the SHORT is a Futures position at 5x.
                        direction == Direction.LONG ? MarketType.SPOT : MarketType.FUTURES,
                        direction,
                        direction == Direction.LONG ? null : 5,
                        new BigDecimal("101"),
                        FILLED_AT,
                        new BigDecimal("2"),
                        LiquidityRole.TAKER,
                        new BigDecimal("0.2"),
                        direction == Direction.LONG ? new BigDecimal("95") : new BigDecimal("106"),
                        direction == Direction.LONG ? new BigDecimal("120") : new BigDecimal("80"),
                        null,
                        learning)));
    }

    private void close(OpenTrade trade, String adverse, String favorable) {
        close(trade, adverse, favorable, FILLED_AT.plusSeconds(3600));
    }

    private void close(OpenTrade trade, String adverse, String favorable, Instant exitTime) {
        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> assertThat(journal.closeSimulatedTrade(new SimulatedTradeClosing(
                                trade.positionId(),
                                trade.version(),
                                new BigDecimal("113"),
                                exitTime,
                                CloseReason.TAKE_PROFIT,
                                new BigDecimal("24"),
                                new BigDecimal("0.2"),
                                null,
                                false,
                                List.of(),
                                new PositionExcursion(
                                        trade.positionId(), new BigDecimal(adverse), new BigDecimal(favorable)))))
                        .isTrue());
    }

    private void widen(UUID id, String adverse, String favorable) {
        journal.widenExcursions(List.of(new PositionExcursion(id, new BigDecimal(adverse), new BigDecimal(favorable))));
    }

    private void assertExtremes(UUID id, String adverse, String favorable) {
        Map<String, Object> row = row(id);
        assertThat((BigDecimal) row.get("max_adverse_price")).isEqualByComparingTo(adverse);
        assertThat((BigDecimal) row.get("max_favorable_price")).isEqualByComparingTo(favorable);
    }

    private Map<String, Object> row(UUID id) {
        return sql.sql("select * from trading_journal where journal_id = ?")
                .param(id)
                .query()
                .singleRow();
    }

    /** Planned at 100 with risk 10 on 1h and SWING v1; the deviation 0.2 R is the trading module's value. */
    private static SimulatedFillLearning learning(String atPlan, String atFill, Boolean warned) {
        return new SimulatedFillLearning(
                new BigDecimal("100"),
                new BigDecimal("0.2"),
                BigDecimal.TEN,
                "1h",
                "SWING v1",
                new BigDecimal(atPlan),
                atFill == null ? null : new BigDecimal(atFill),
                warned,
                new ClosedCandleIndicators(
                        "1h",
                        CANDLE,
                        new BigDecimal("48.25"),
                        new BigDecimal("99.5"),
                        new BigDecimal("98.1"),
                        new BigDecimal("90.1234567891"),
                        new BigDecimal("0.31"),
                        new BigDecimal("0.35"),
                        new BigDecimal("-0.04")));
    }

    private UUID plan(UUID owner) {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into trading_plan (plan_id, user_id, pair_id, market_type, direction, entry_type,
                                                  entry_price, stop_loss_price, take_profit_price, capital_amount,
                                                  risk_percent, plan_status, analysis_timeframe, created_at, updated_at)
                        values (?, ?, ?, 'SPOT', 'LONG', 'LIMIT', 100, 95, 120, 1000, 1, 'DRAFT', '1h', ?, ?)""").params(id, owner, pair, ts(FILLED_AT), ts(FILLED_AT)).update();
        return id;
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""")
                .params(id, id + "@learning.invalid", ts(FILLED_AT), ts(FILLED_AT))
                .update();
        accounts.add(id);
        return id;
    }

    private org.springframework.test.web.servlet.ResultActions detail(UUID user, UUID id) throws Exception {
        return mvc.perform(get("/api/v1/journal/" + id).header(HttpHeaders.AUTHORIZATION, bearer(user)));
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
