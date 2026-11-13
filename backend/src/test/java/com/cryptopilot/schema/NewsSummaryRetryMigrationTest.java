package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.support.TestcontainersConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V69 run over articles written at V68: an article whose last summary failure was the provider's (overloaded, too
 * slow, rate limited or out of reach) gets its attempts back; one the model failed on, a summarized one and a hidden
 * one are left as they were.
 *
 * <p>Runs in a database of its own on the shared container, migrated to V68 first, as {@link
 * BannedContentMigrationTest}.
 *
 * <p>Rule: NSF-11; D-128.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class NewsSummaryRetryMigrationTest {

    private static final String DATABASE = "cryptopilot_v69";

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-05T08:00:00Z");

    @Autowired
    private PostgreSQLContainer container;

    @Autowired
    private Flyway configuredFlyway;

    private String url;
    private SingleConnectionDataSource dataSource;
    private JdbcClient jdbc;
    private UUID source;
    private UUID administrator;

    @BeforeEach
    void migrateToV68() throws SQLException {
        String adminUrl = container.getJdbcUrl();
        try (Connection connection =
                        DriverManager.getConnection(adminUrl, container.getUsername(), container.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE);
        }
        url = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + DATABASE;
        flyway("68").migrate();

        dataSource = new SingleConnectionDataSource(url, container.getUsername(), container.getPassword(), true);
        jdbc = JdbcClient.create(dataSource);
        source = insertSource();
        administrator = insertAdministrator();
    }

    @AfterEach
    void release() {
        if (dataSource != null) {
            dataSource.destroy();
            dataSource = null;
        }
    }

    @Test
    void NSF11_articlesFailedOnlyByTheProvider_getTheirAttemptsBack_andTheOthersAreLeftAlone() {
        UUID overloaded = insertArticle(3, "LLM_UNAVAILABLE: Gemini failed (HTTP 503)", null, false);
        UUID slow = insertArticle(2, "LLM_TIMEOUT: Gemini did not answer in time", null, false);
        UUID rateLimited = insertArticle(3, "LLM_RATE_LIMITED: quota", null, false);
        UUID unreachable = insertArticle(1, "UNREACHABLE: connection refused", null, false);
        UUID modelFailed = insertArticle(3, "the summary repeats a number not in the article", null, false);
        UUID summarized = insertArticle(1, "LLM_UNAVAILABLE: Gemini failed (HTTP 503)", "Tóm tắt", false);
        UUID hidden = insertArticle(3, "LLM_UNAVAILABLE: Gemini failed (HTTP 503)", null, true);

        flyway("latest").migrate();

        for (UUID reset : new UUID[] {overloaded, slow, rateLimited, unreachable}) {
            Map<String, Object> row = row(reset);
            assertThat(row.get("summary_attempts")).isEqualTo(0);
            assertThat(row.get("summary_error")).isNull();
            assertThat(row.get("summary_attempted_at")).isNull();
            assertThat(row.get("version")).isEqualTo(1L);
        }
        assertThat(row(modelFailed).get("summary_attempts")).isEqualTo(3);
        assertThat(row(summarized).get("summary_attempts")).isEqualTo(1);
        assertThat(row(hidden).get("summary_attempts")).isEqualTo(3);
        assertThat(row(hidden).get("version")).isEqualTo(0L);
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(url, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .placeholders(configuredFlyway.getConfiguration().getPlaceholders())
                .target(target)
                .load();
    }

    private UUID insertSource() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into news_source (source_id, source_name, feed_url, source_type, crawl_interval_minutes,
                                                 is_active, created_at, updated_at)
                        values (?, 'Outlet', 'https://outlet.example/feed', 'RSS', 15, true, ?, ?)""").params(id, AT, AT).update();
        return id;
    }

    private UUID insertAdministrator() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'ADMIN', 'ACTIVE', ?, ?)""").params(id, id + "@v69.invalid", AT, AT).update();
        return id;
    }

    private UUID insertArticle(int attempts, String error, String summary, boolean hidden) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into news_article (article_id, source_id, title, original_url, url_hash, title_hash,
                                                  ai_summary, published_at, collected_at, created_at, updated_at,
                                                  summary_attempts, summary_error, summary_attempted_at, hidden_at,
                                                  hidden_by)
                        values (?, ?, 'Title', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(
                        id,
                        source,
                        "https://outlet.example/" + id,
                        id.toString(),
                        id.toString(),
                        summary,
                        AT,
                        AT,
                        AT,
                        AT,
                        attempts,
                        error,
                        AT,
                        hidden ? AT : null,
                        hidden ? administrator : null)
                .update();
        return id;
    }

    private Map<String, Object> row(UUID article) {
        return jdbc.sql("""
                        select summary_attempts, summary_error, summary_attempted_at, version
                          from news_article where article_id = ?""").param(article).query().singleRow();
    }
}
