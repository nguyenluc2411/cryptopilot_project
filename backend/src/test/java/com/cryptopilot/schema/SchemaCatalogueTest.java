package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.support.TestcontainersConfig;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Rules the schema has to keep as it grows, each read out of the system catalogue rather than from
 * a list somebody has to remember to extend. A table added in a later task is covered by these
 * tests the moment it exists.
 *
 * <p>Rule: SRS 3.1.5; TECHNICAL_DESIGN sections 5.4, 5.5 and 6; ADR-009 (the application assigns
 * the identifier, never the database).
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class SchemaCatalogueTest {

    /**
     * The only deletes that may cascade: an aggregate root removing rows that are part of it. Every
     * other foreign key refuses a delete that would orphan a row, which is what keeps trading
     * history, orders, community content and the audit trail alive when somebody tries to remove
     * the account that produced them.
     */
    private static final Set<String> ALLOWED_CASCADES = Set.of(
            "user_profile.user_id -> user_account",
            "user_token.user_id -> user_account",
            "user_device.user_id -> user_account",
            "leverage_bracket.pair_id -> crypto_pair",
            "alert.watchlist_id -> watchlist",
            "trading_plan_warning.plan_id -> trading_plan",
            "ai_message.conversation_id -> ai_conversation",
            "post_coin.post_id -> forum_post",
            "forum_comment.post_id -> forum_post",
            "forum_comment.parent_comment_id -> forum_comment",
            "engagement.post_id -> forum_post",
            "engagement.comment_id -> forum_comment",
            "post_media.post_id -> forum_post",
            "post_report.post_id -> forum_post",
            "news_crawl_log.source_id -> news_source",
            "news_article_tag.article_id -> news_article",
            "news_article_tag.tag_id -> news_tag",
            "news_article_coin.article_id -> news_article");

    @Autowired
    private JdbcClient jdbc;

    /**
     * The precision of a value follows from what the value is, so the test derives the expectation
     * from the column name instead of listing columns one by one: a column added later is checked
     * without anyone editing this test. The amounts of a table that also carries a currency are
     * VND, which has no sub-unit and therefore scale zero.
     */
    @Test
    void everyNumericColumn_carriesThePrecisionOfItsKind() {
        List<Map<String, Object>> columns = jdbc.sql("""
                        select c.table_name, c.column_name, c.data_type,
                               c.numeric_precision::int as numeric_precision,
                               c.numeric_scale::int as numeric_scale,
                               exists (select 1
                                         from information_schema.columns cur
                                        where cur.table_schema = c.table_schema
                                          and cur.table_name = c.table_name
                                          and cur.column_name = 'currency') as priced_in_vnd
                          from information_schema.columns c
                         where c.table_schema = 'public'
                           and c.table_name <> 'flyway_schema_history'
                         order by c.table_name, c.column_name""").query().listOfRows();

        List<String> wrong = columns.stream()
                .map(SchemaCatalogueTest::precisionComplaint)
                .filter(complaint -> complaint != null)
                .toList();

        assertThat(wrong)
                .as("every price, quantity, amount, rate, score and percent column of TECHNICAL_DESIGN 5.4")
                .isEmpty();
    }

    private static String precisionComplaint(Map<String, Object> column) {
        String name = String.valueOf(column.get("column_name"));
        boolean vnd = Boolean.TRUE.equals(column.get("priced_in_vnd"));
        int[] kind = expectedPrecision(name, vnd);
        if (kind == null) {
            return null;
        }
        String where = column.get("table_name") + "." + name;
        if (!"numeric".equals(column.get("data_type"))) {
            return where + " is " + column.get("data_type") + ", expected numeric";
        }
        int precision = (int) column.get("numeric_precision");
        int scale = (int) column.get("numeric_scale");
        if (precision != kind[0] || scale != kind[1]) {
            return "%s is numeric(%d,%d), expected numeric(%d,%d)".formatted(where, precision, scale, kind[0], kind[1]);
        }
        return null;
    }

    private static int[] expectedPrecision(String column, boolean pricedInVnd) {
        if (column.endsWith("_price") || column.endsWith("_quantity") || column.equals("quantity")) {
            return new int[] {28, 12};
        }
        if (column.endsWith("_amount") || column.equals("amount")) {
            return pricedInVnd ? new int[] {18, 0} : new int[] {28, 8};
        }
        if (column.endsWith("_rate")) {
            return new int[] {12, 8};
        }
        if (column.endsWith("_score")) {
            return new int[] {5, 2};
        }
        if (column.endsWith("_percent")) {
            return new int[] {6, 3};
        }
        return null;
    }

    @Test
    void onlyAggregateRoots_cascadeTheirDeletes() {
        List<String> cascades = jdbc.sql("""
                        select c.conrelid::regclass::text || '.' || a.attname
                                 || ' -> ' || c.confrelid::regclass::text
                          from pg_constraint c
                          join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1]
                         where c.contype = 'f'
                           and c.connamespace = 'public'::regnamespace
                           and c.confdeltype = 'c'
                         order by 1""").query(String.class).list();

        assertThat(cascades).containsExactlyInAnyOrderElementsOf(ALLOWED_CASCADES);
    }

    @Test
    void noForeignKeyToUserAccount_cascades() {
        List<String> cascading = jdbc.sql("""
                        select c.conrelid::regclass::text
                          from pg_constraint c
                         where c.contype = 'f'
                           and c.connamespace = 'public'::regnamespace
                           and c.confrelid = 'user_account'::regclass
                           and c.confdeltype = 'c'
                         order by 1""").query(String.class).list();

        assertThat(cascading)
                .as("an account is never hard-deleted, so only the rows that are the account itself follow it")
                .containsExactly("user_device", "user_profile", "user_token");
    }

    @Test
    void noUuidPrimaryKey_hasADatabaseDefault() {
        List<String> generated = jdbc.sql("""
                        select c.table_name || '.' || c.column_name || ' default ' || c.column_default
                          from information_schema.columns c
                          join information_schema.key_column_usage k
                            on k.table_schema = c.table_schema
                           and k.table_name = c.table_name
                           and k.column_name = c.column_name
                          join information_schema.table_constraints t
                            on t.constraint_name = k.constraint_name
                           and t.table_schema = k.table_schema
                           and t.constraint_type = 'PRIMARY KEY'
                         where c.table_schema = 'public'
                           and c.data_type = 'uuid'
                           and c.column_default is not null
                         order by 1""").query(String.class).list();

        assertThat(generated)
                .as("the application assigns uuid v7 before the insert, so the database never generates one")
                .isEmpty();
    }

    /**
     * The registry belongs to Spring Modulith, not to this design: its structure is whatever the
     * version on the classpath reads and writes. The expectation below is the postgresql schema of
     * {@code spring-modulith-events-jdbc} 2.1.1 ({@code schemas/v2/schema-postgresql.sql}), the
     * version the Spring Modulith bom pins for Spring Boot 4.1.1. Types and nullability are
     * asserted, not only names, because a column of the wrong type fails at the first publication
     * rather than at the migration.
     */
    @Test
    void eventPublication_matchesTheSchemaOfThePinnedSpringModulithVersion() {
        List<String> columns = jdbc.sql("""
                        select column_name || ' ' || data_type
                                 || (case when is_nullable = 'NO' then ' NOT NULL' else '' end)
                          from information_schema.columns
                         where table_schema = 'public' and table_name = 'event_publication'
                         order by ordinal_position""").query(String.class).list();

        assertThat(columns)
                .containsExactly(
                        "id uuid NOT NULL",
                        "listener_id text NOT NULL",
                        "event_type text NOT NULL",
                        "serialized_event text NOT NULL",
                        "publication_date timestamp with time zone NOT NULL",
                        "completion_date timestamp with time zone",
                        "status text",
                        "completion_attempts integer",
                        "last_resubmission_date timestamp with time zone");
    }

    @Test
    void eventPublication_carriesTheTwoIndexesTheRegistryQueriesThrough() {
        List<String> indexes = jdbc.sql("""
                        select indexname
                          from pg_indexes
                         where schemaname = 'public' and tablename = 'event_publication'
                         order by indexname""").query(String.class).list();

        assertThat(indexes)
                .containsExactly(
                        "event_publication_by_completion_date_idx",
                        "event_publication_pkey",
                        "event_publication_serialized_event_hash_idx");
    }
}
