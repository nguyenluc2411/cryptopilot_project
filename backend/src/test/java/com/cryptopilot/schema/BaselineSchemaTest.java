package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.support.TestcontainersConfig;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Checks the shape of the schema the baseline migration produces, against the database it is meant
 * for rather than against a substitute dialect.
 *
 * <p>The assertions are written over the system catalogue instead of table by table, so a table
 * added later without a primary key, or a foreign key added without its index, fails here without
 * anyone remembering to extend the test.
 *
 * <p>Rule: SRS 3.1.5; TECHNICAL_DESIGN sections 5.4 and 6; D-18; A-09.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class BaselineSchemaTest {

    /** The 38 business tables of the logical model, in the order the model lists them. */
    private static final List<String> BUSINESS_TABLES = List.of(
            "user_account",
            "user_profile",
            "user_token",
            "user_device",
            "coin",
            "crypto_pair",
            "ohlcv",
            "technical_indicator",
            "spot_market_data",
            "futures_market_data",
            "watchlist",
            "alert",
            "notification",
            "trading_strategy",
            "trading_plan",
            "trading_plan_warning",
            "trading_journal",
            "ai_conversation",
            "ai_message",
            "ai_configuration",
            "subscription_package",
            "subscription_order",
            "user_subscription",
            "forum_post",
            "post_coin",
            "forum_comment",
            "engagement",
            "post_media",
            "post_report",
            "news_source",
            "news_crawl_log",
            "news_article",
            "news_tag",
            "news_article_tag",
            "news_article_coin",
            "leverage_bracket",
            "system_setting",
            "audit_log");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Flyway flyway;

    @Test
    void migration_appliedSuccessfullyOnACleanDatabase() {
        Map<String, Object> applied = jdbc.sql(
                        "select version, description, success from flyway_schema_history where version = '1'")
                .query()
                .singleRow();

        assertThat(applied.get("description")).isEqualTo("baseline schema");
        assertThat(applied.get("success")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void migration_reRun_changesNothing() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void schema_holdsTheThirtyEightBusinessTables() {
        assertThat(tableNames()).containsAll(BUSINESS_TABLES);
        assertThat(BUSINESS_TABLES).hasSize(38);
    }

    @Test
    void schema_holdsTheTechnicalTablesAndNothingElse() {
        assertThat(tableNames())
                .as("event_publication and flyway_schema_history are technical tables, not business entities")
                .contains("event_publication", "flyway_schema_history")
                .hasSize(BUSINESS_TABLES.size() + 2);
    }

    @Test
    void everyTable_hasAPrimaryKey() {
        List<String> withoutPrimaryKey = jdbc.sql("""
                        select t.table_name
                          from information_schema.tables t
                         where t.table_schema = 'public'
                           and t.table_type = 'BASE TABLE'
                           and not exists (
                               select 1
                                 from information_schema.table_constraints c
                                where c.table_schema = t.table_schema
                                  and c.table_name = t.table_name
                                  and c.constraint_type = 'PRIMARY KEY')
                         order by t.table_name""").query(String.class).list();

        assertThat(withoutPrimaryKey).isEmpty();
    }

    /**
     * PostgreSQL indexes the parent side of a foreign key but not the child side. Without an index
     * on the referencing column, deleting one parent row scans the whole child table, which on
     * ohlcv or notification is the difference between a query and an outage.
     */
    @Test
    void everyForeignKeyColumn_isTheLeadingColumnOfSomeIndex() {
        List<String> unindexed = jdbc.sql("""
                        select c.conrelid::regclass::text || '.' || a.attname
                          from pg_constraint c
                          join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1]
                         where c.contype = 'f'
                           and c.connamespace = 'public'::regnamespace
                           and not exists (
                               select 1
                                 from pg_index i
                                where i.indrelid = c.conrelid
                                  and i.indkey[0] = c.conkey[1]
                                  and i.indpred is null)
                         order by 1""").query(String.class).list();

        assertThat(unindexed).isEmpty();
    }

    /**
     * Flyway's own history table is excluded: its {@code installed_on} column is a plain timestamp
     * chosen by Flyway, and it is not a column this design owns.
     */
    @Test
    void everyInstantColumn_isTimestampWithTimeZone() {
        List<String> naiveTimestamps = jdbc.sql("""
                        select table_name || '.' || column_name
                          from information_schema.columns
                         where table_schema = 'public'
                           and table_name <> 'flyway_schema_history'
                           and data_type = 'timestamp without time zone'
                         order by 1""").query(String.class).list();

        assertThat(naiveTimestamps)
                .as("every instant of our own schema is stored in UTC as timestamptz")
                .isEmpty();
    }

    /**
     * Both tables become hypertables in T-006, and a hypertable-to-hypertable foreign key is not
     * supported in every TimescaleDB version. The absence of this constraint is a design decision,
     * so it is asserted rather than left to be re-added by mistake.
     */
    @Test
    void technicalIndicator_hasNoForeignKeyToOhlcv() {
        List<String> references = jdbc.sql("""
                        select conname
                          from pg_constraint
                         where contype = 'f'
                           and conrelid = 'technical_indicator'::regclass
                           and confrelid = 'ohlcv'::regclass""").query(String.class).list();

        assertThat(references).isEmpty();
    }

    @ParameterizedTest(name = "{0}.{1} is {2}({3},{4})")
    @CsvSource({
        "ohlcv, close_price, numeric, 28, 12",
        "crypto_pair, spot_tick_size, numeric, 28, 12",
        "trading_plan, position_quantity, numeric, 28, 12",
        "trading_plan, capital_amount, numeric, 28, 8",
        "trading_plan, risk_percent, numeric, 6, 3",
        "trading_journal, realized_pnl, numeric, 28, 8",
        "futures_market_data, funding_rate, numeric, 12, 8",
        "leverage_bracket, maintenance_margin_rate, numeric, 12, 8",
        "technical_indicator, rsi_14, numeric, 28, 10",
        "technical_indicator, setup_score, numeric, 5, 2",
        "subscription_package, price_amount, numeric, 18, 0",
        "subscription_order, amount, numeric, 18, 0"
    })
    void numericColumns_carryThePrecisionOfTheirKind(
            String table, String column, String type, int precision, int scale) {
        Map<String, Object> actual = jdbc.sql("""
                        select data_type, numeric_precision::int as numeric_precision, numeric_scale::int as numeric_scale
                          from information_schema.columns
                         where table_schema = 'public' and table_name = ? and column_name = ?""").params(table, column).query().singleRow();

        assertThat(actual.get("data_type")).isEqualTo(type);
        assertThat(actual.get("numeric_precision")).isEqualTo(precision);
        assertThat(actual.get("numeric_scale")).isEqualTo(scale);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "uq_watchlist_user_pair",
                "uq_post_report_post_reporter",
                "uq_trading_journal_plan",
                "uq_user_subscription_order",
                "uq_subscription_order_code",
                "uq_news_article_url_hash",
                "uq_user_account_email",
                "uq_coin_symbol",
                "uq_crypto_pair_symbol"
            })
    void uniqueConstraintsOfTheLogicalModel_exist(String constraintName) {
        assertThat(constraintExists(constraintName)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "uq_engagement_user_post",
                "uq_engagement_user_comment",
                "uq_ai_configuration_active",
                "uq_user_account_email_lower",
                "idx_trading_plan_active",
                "idx_trading_journal_open",
                "idx_alert_active",
                "idx_notification_user_unread",
                "idx_user_subscription_status_end",
                "idx_news_article_title_hash",
                "idx_news_article_feed",
                "idx_subscription_order_status"
            })
    void indexesOfTheDesign_exist(String indexName) {
        Integer count = jdbc.sql("select count(*) from pg_indexes where schemaname = 'public' and indexname = ?")
                .param(indexName)
                .query(Integer.class)
                .single();

        assertThat(count).isEqualTo(1);
    }

    @Test
    void eventPublication_matchesTheStructureSpringModulithExpects() {
        List<String> columns = jdbc.sql("""
                        select column_name
                          from information_schema.columns
                         where table_schema = 'public' and table_name = 'event_publication'
                         order by column_name""").query(String.class).list();

        assertThat(columns)
                .containsExactly(
                        "completion_attempts",
                        "completion_date",
                        "event_type",
                        "id",
                        "last_resubmission_date",
                        "listener_id",
                        "publication_date",
                        "serialized_event",
                        "status");
    }

    private List<String> tableNames() {
        return jdbc.sql("""
                        select table_name
                          from information_schema.tables
                         where table_schema = 'public' and table_type = 'BASE TABLE'
                         order by table_name""").query(String.class).list();
    }

    private boolean constraintExists(String constraintName) {
        Integer count = jdbc.sql(
                        "select count(*) from pg_constraint where connamespace = 'public'::regnamespace and conname = ?")
                .param(constraintName)
                .query(Integer.class)
                .single();
        return count != null && count == 1;
    }
}
