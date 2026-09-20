package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.support.TestcontainersConfig;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exercises the rules the schema is supposed to enforce by trying to break them. A constraint that
 * is never violated in a test is a constraint nobody has checked: the interesting assertion is that
 * the database refuses the row, not that a correct row goes in.
 *
 * <p>Every test runs in a transaction that is rolled back afterwards, so the rows below never
 * outlive the test that wrote them. Each test that expects a violation makes the failing statement
 * its last one, because in PostgreSQL a failed statement leaves the transaction unusable.
 *
 * <p>Four of the rules below carry a business rule id in the test name, because the constraint is
 * the rule: BR-15 (a pair is watched once per user), BR-45 (one like and one save per user per
 * target), BR-46 (one report per user per post) and BR-49 (a source is polled at most every
 * fifteen minutes). The database half is what is proved here; the limits, messages and service
 * behaviour of those rules belong to the tasks that own them.
 *
 * <p>Rule: SRS 3.1.5, BR-15, BR-45, BR-46, BR-49, and the rules the logical model states in its
 * column comments.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class SchemaConstraintTest {

    /**
     * The PostgreSQL driver binds an {@link OffsetDateTime} but not an {@link Instant}, so the
     * conversion happens once here rather than at every call site.
     */
    private static final OffsetDateTime NOW =
            Instant.parse("2026-09-20T00:00:00Z").atOffset(ZoneOffset.UTC);

    @Autowired
    private JdbcClient jdbc;

    private UUID userId;
    private UUID pairId;

    @BeforeEach
    void insertReferenceRows() {
        userId = insertUser("trader@cryptopilot.test");
        pairId = insertPair("BTCUSDT");
    }

    // ---------------------------------------------------------------- enumerations

    @Test
    void enumColumn_rejectsAValueOutsideItsList() {
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> jdbc.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status,
                                              created_at, updated_at)
                        values (?, ?, 'hash', 'SUPERVISOR', 'ACTIVE', ?, ?)""")
                .params(UUID.randomUUID(), "wrong-role@cryptopilot.test", NOW, NOW)
                .update());
    }

    @Test
    void enumColumn_acceptsAValueFromItsList() {
        int inserted = jdbc.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status,
                                              created_at, updated_at)
                        values (?, ?, 'hash', 'ADMIN', 'BANNED', ?, ?)""")
                .params(UUID.randomUUID(), "admin@cryptopilot.test", NOW, NOW)
                .update();

        assertThat(inserted).isEqualTo(1);
    }

    // ---------------------------------------------------------------- identity

    @Test
    void email_isUniqueRegardlessOfCase() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertUser("Trader@CryptoPilot.test"));
    }

    // ---------------------------------------------------------------- watchlist and alerts

    @Test
    void BR15_samePairAddedTwiceByOneUser_isRejected() {
        insertWatchlist();

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(this::insertWatchlist);
    }

    @Test
    void priceAlert_mustNotNameAnIndicator() {
        UUID watchlistId = insertWatchlist();

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> jdbc.sql("""
                        insert into alert (alert_id, user_id, watchlist_id, market_type, alert_type,
                                           indicator_name, timeframe, condition_operator, threshold_value,
                                           trigger_mode, alert_status, created_at, updated_at)
                        values (?, ?, ?, 'SPOT', 'PRICE', 'RSI_14', '1h', 'CROSS_ABOVE', 70,
                                'ONCE', 'ACTIVE', ?, ?)""")
                .params(UUID.randomUUID(), userId, watchlistId, NOW, NOW)
                .update());
    }

    @Test
    void indicatorAlert_mustNameAnIndicatorAndATimeframe() {
        UUID watchlistId = insertWatchlist();

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> jdbc.sql("""
                        insert into alert (alert_id, user_id, watchlist_id, market_type, alert_type,
                                           condition_operator, threshold_value, trigger_mode, alert_status,
                                           created_at, updated_at)
                        values (?, ?, ?, 'SPOT', 'INDICATOR', 'CROSS_ABOVE', 70, 'ONCE', 'ACTIVE', ?, ?)""")
                .params(UUID.randomUUID(), userId, watchlistId, NOW, NOW)
                .update());
    }

    // ---------------------------------------------------------------- trading plans

    @Test
    void spotPlan_cannotBeShort() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertPlan("SPOT", "SHORT", null));
    }

    @Test
    void spotPlan_cannotCarryLeverage() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertPlan("SPOT", "LONG", 10));
    }

    @Test
    void futuresPlan_mayBeShortAndLeveraged() {
        assertThat(insertPlan("FUTURES", "SHORT", 10)).isNotNull();
    }

    @Test
    void limitPlan_needsTheEntryPriceItWaitsAt() {
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> jdbc.sql("""
                        insert into trading_plan (plan_id, user_id, pair_id, market_type, direction,
                                                  entry_type, stop_loss_price, take_profit_price,
                                                  capital_amount, risk_percent, plan_status,
                                                  created_at, updated_at)
                        values (?, ?, ?, 'SPOT', 'LONG', 'LIMIT', 26500, 28000, 1000, 1.5, 'DRAFT', ?, ?)""")
                .params(UUID.randomUUID(), userId, pairId, NOW, NOW)
                .update());
    }

    // ---------------------------------------------------------------- journal

    @Test
    void simulatedTrade_mustComeFromAPlan() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertJournal("SIMULATED", null));
    }

    @Test
    void manualTrade_mustNotComeFromAPlan() {
        UUID planId = insertPlan("FUTURES", "LONG", 5);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertJournal("MANUAL", planId));
    }

    @Test
    void onePlan_producesAtMostOneJournalRecord() {
        UUID planId = insertPlan("FUTURES", "LONG", 5);
        insertJournal("SIMULATED", planId);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertJournal("SIMULATED", planId));
    }

    @Test
    void manualTrades_areNotLimitedToOnePerUser() {
        insertJournal("MANUAL", null);

        assertThat(insertJournal("MANUAL", null)).isNotNull();
    }

    // ---------------------------------------------------------------- community

    @Test
    void engagement_rejectsARowWithTwoTargets() {
        UUID postId = insertPost();
        UUID commentId = insertComment(postId);

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> jdbc.sql("""
                        insert into engagement (engagement_id, user_id, post_id, comment_id, action_type, created_at)
                        values (?, ?, ?, ?, 'LIKE', ?)""")
                .params(UUID.randomUUID(), userId, postId, commentId, NOW)
                .update());
    }

    @Test
    void engagement_rejectsARowWithNoTarget() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() ->
                        jdbc.sql("""
                        insert into engagement (engagement_id, user_id, post_id, comment_id, action_type, created_at)
                        values (?, ?, null, null, 'LIKE', ?)""").params(UUID.randomUUID(), userId, NOW).update());
    }

    @Test
    void BR45_secondLikeOnTheSamePost_isRejected() {
        UUID postId = insertPost();
        insertLike(postId);

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> insertLike(postId));
    }

    @Test
    void BR46_secondReportOnTheSamePost_isRejected() {
        UUID postId = insertPost();
        insertReport(postId);

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> insertReport(postId));
    }

    @Test
    void deletingAPost_takesItsCommentsWithIt() {
        UUID postId = insertPost();
        insertComment(postId);

        jdbc.sql("delete from forum_post where post_id = ?").param(postId).update();

        Integer remaining = jdbc.sql("select count(*) from forum_comment where post_id = ?")
                .param(postId)
                .query(Integer.class)
                .single();
        assertThat(remaining).isZero();
    }

    // ---------------------------------------------------------------- configuration

    @Test
    void onlyOneAiConfiguration_isActiveAtATime() {
        insertAiConfiguration(true);

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> insertAiConfiguration(true));
    }

    @Test
    void anyNumberOfAiConfigurations_maySitInactive() {
        insertAiConfiguration(false);

        assertThat(insertAiConfiguration(false)).isNotNull();
    }

    @Test
    void aPair_cannotQuoteItself() {
        UUID coinId = insertCoin("SOLO");

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> jdbc.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol,
                                                 pair_status, updated_at)
                        values (?, ?, ?, 'SOLOSOLO', 'ACTIVE', ?)""")
                .params(UUID.randomUUID(), coinId, coinId, NOW)
                .update());
    }

    @Test
    void BR49_crawlIntervalBelowFifteenMinutes_isRejected() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.sql("""
                        insert into news_source (source_id, source_name, feed_url, source_type,
                                                 crawl_interval_minutes)
                        values (?, 'Too eager', 'https://example.test/feed', 'RSS', 5)""").param(UUID.randomUUID()).update());
    }

    // ---------------------------------------------------------------- helpers

    private UUID insertUser(String email) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status,
                                              created_at, updated_at)
                        values (?, ?, 'hash', 'TRADER', 'ACTIVE', ?, ?)""").params(id, email, NOW, NOW).update();
        return id;
    }

    private UUID insertCoin(String symbol) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into coin (coin_id, symbol, coin_name, created_at) values (?, ?, ?, ?)")
                .params(id, symbol, symbol + " coin", NOW)
                .update();
        return id;
    }

    private UUID insertPair(String symbol) {
        UUID base = insertCoin(symbol.replace("USDT", ""));
        UUID quote = insertCoin("USDT");
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol,
                                                 is_spot_enabled, is_futures_enabled, pair_status, updated_at)
                        values (?, ?, ?, ?, true, true, 'ACTIVE', ?)""").params(id, base, quote, symbol, NOW).update();
        return id;
    }

    private UUID insertWatchlist() {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into watchlist (watchlist_id, user_id, pair_id, added_at) values (?, ?, ?, ?)")
                .params(id, userId, pairId, NOW)
                .update();
        return id;
    }

    private UUID insertPlan(String marketType, String direction, Integer leverage) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into trading_plan (plan_id, user_id, pair_id, market_type, direction,
                                                  entry_type, entry_price, stop_loss_price, take_profit_price,
                                                  capital_amount, risk_percent, leverage, margin_mode,
                                                  plan_status, created_at, updated_at)
                        values (?, ?, ?, ?, ?, 'LIMIT', 27123.45, 26500, 28000, 1000, 1.5,
                                cast(? as integer), cast(? as varchar), 'DRAFT', ?, ?)""")
                .params(
                        id,
                        userId,
                        pairId,
                        marketType,
                        direction,
                        leverage,
                        leverage == null ? null : "ISOLATED",
                        NOW,
                        NOW)
                .update();
        return id;
    }

    private UUID insertJournal(String source, UUID planId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into trading_journal (journal_id, user_id, pair_id, plan_id, source, market_type,
                                                     direction, entry_price, quantity, entry_time, trade_status,
                                                     created_at, updated_at)
                        values (?, ?, ?, cast(? as uuid), ?, 'FUTURES', 'LONG', 27123.45, 0.024, ?, 'OPEN', ?, ?)""")
                .params(id, userId, pairId, planId == null ? null : planId.toString(), source, NOW, NOW, NOW)
                .update();
        return id;
    }

    private UUID insertPost() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into forum_post (post_id, author_id, post_type, title, content, post_status,
                                                created_at, updated_at)
                        values (?, ?, 'TEXT', 'Title', 'Content', 'PUBLISHED', ?, ?)""").params(id, userId, NOW, NOW).update();
        return id;
    }

    private UUID insertComment(UUID postId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into forum_comment (comment_id, post_id, author_id, content, comment_status,
                                                   created_at, updated_at)
                        values (?, ?, ?, 'first', 'PUBLISHED', ?, ?)""").params(id, postId, userId, NOW, NOW).update();
        return id;
    }

    private void insertLike(UUID postId) {
        jdbc.sql("""
                        insert into engagement (engagement_id, user_id, post_id, action_type, created_at)
                        values (?, ?, ?, 'LIKE', ?)""").params(UUID.randomUUID(), userId, postId, NOW).update();
    }

    private void insertReport(UUID postId) {
        jdbc.sql("""
                        insert into post_report (report_id, post_id, reporter_id, reason, report_status, created_at)
                        values (?, ?, ?, 'SPAM', 'OPEN', ?)""").params(UUID.randomUUID(), postId, userId, NOW).update();
    }

    private UUID insertAiConfiguration(boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into ai_configuration (config_id, model_name, system_prompt, temperature,
                                                      max_output_tokens, is_active, created_at)
                        values (?, 'model', 'prompt', 0.20, 1024, ?, ?)""").params(id, active, NOW).update();
        return id;
    }
}
