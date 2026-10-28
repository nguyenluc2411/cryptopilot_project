package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.support.TestcontainersConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
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
 * V48 run over rows written at V47: the published posts and comments of a member banned before the migration are
 * hidden, as a ban hides them from then on; other members' content, and what was already hidden or deleted, is left
 * as it was.
 *
 * <p>Runs in a database of its own on the shared container, migrated to V47 first, as {@link FillPriceMigrationTest}.
 *
 * <p>Rule: BR-05, BR-47; D-125.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class BannedContentMigrationTest {

    private static final String DATABASE = "cryptopilot_v48";

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-01T08:00:00Z");

    @Autowired
    private PostgreSQLContainer container;

    @Autowired
    private Flyway configuredFlyway;

    private String url;
    private SingleConnectionDataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void migrateToV47() throws SQLException {
        String adminUrl = container.getJdbcUrl();
        try (Connection connection =
                        DriverManager.getConnection(adminUrl, container.getUsername(), container.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE);
        }
        url = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + DATABASE;
        flyway("47").migrate();

        dataSource = new SingleConnectionDataSource(url, container.getUsername(), container.getPassword(), true);
        jdbc = JdbcClient.create(dataSource);
    }

    @AfterEach
    void release() {
        if (dataSource != null) {
            dataSource.destroy();
            dataSource = null;
        }
    }

    @Test
    void D125_aMemberBannedBeforeV48_hasTheirPublishedPostsAndCommentsHidden() {
        UUID banned = insertAccount("BANNED");
        UUID active = insertAccount("ACTIVE");
        UUID bannedPost = insertPost(banned, "PUBLISHED");
        UUID bannedDeletedPost = insertPost(banned, "DELETED");
        UUID activePost = insertPost(active, "PUBLISHED");
        UUID bannedComment = insertComment(activePost, banned, "PUBLISHED");
        UUID bannedDeletedComment = insertComment(activePost, banned, "DELETED");
        UUID activeComment = insertComment(bannedPost, active, "PUBLISHED");

        flyway("latest").migrate();

        assertThat(postStatus(bannedPost)).isEqualTo("HIDDEN");
        assertThat(postStatus(bannedDeletedPost)).isEqualTo("DELETED");
        assertThat(postStatus(activePost)).isEqualTo("PUBLISHED");
        assertThat(commentStatus(bannedComment)).isEqualTo("HIDDEN");
        assertThat(commentStatus(bannedDeletedComment)).isEqualTo("DELETED");
        assertThat(commentStatus(activeComment)).isEqualTo("PUBLISHED");
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(url, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .placeholders(configuredFlyway.getConfiguration().getPlaceholders())
                .target(target)
                .load();
    }

    private UUID insertAccount(String status) {
        UUID id = UUID.randomUUID();
        String insert = """
                insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                          updated_at)
                values (?, ?, 'x', 'TRADER', ?, ?, ?)""";
        jdbc.sql(insert).params(id, id + "@v48.invalid", status, AT, AT).update();
        return id;
    }

    private UUID insertPost(UUID author, String status) {
        UUID id = UUID.randomUUID();
        String insert = """
                insert into forum_post (post_id, author_id, title, content, post_type, post_status,
                                        is_official, created_at, updated_at)
                values (?, ?, 'Post', 'text', 'TEXT', ?, false, ?, ?)""";
        jdbc.sql(insert).params(id, author, status, AT, AT).update();
        return id;
    }

    private UUID insertComment(UUID post, UUID author, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into forum_comment (comment_id, post_id, author_id, content, comment_status,
                                                   created_at, updated_at)
                        values (?, ?, ?, 'comment', ?, ?, ?)""").params(id, post, author, status, AT, AT).update();
        return id;
    }

    private String postStatus(UUID post) {
        return jdbc.sql("select post_status from forum_post where post_id = ?")
                .param(post)
                .query(String.class)
                .single();
    }

    private String commentStatus(UUID comment) {
        return jdbc.sql("select comment_status from forum_comment where comment_id = ?")
                .param(comment)
                .query(String.class)
                .single();
    }
}
