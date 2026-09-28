package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.support.TestcontainersConfig;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileCopyUtils;

/**
 * The risk profile column V12 adds to {@code user_profile}: every existing and new profile holds CONSERVATIVE unless one
 * is chosen (D-64), only the three profiles are stored, and running the migration again changes nothing.
 *
 * <p>Every test rolls back.
 *
 * <p>Rule: BR-66; SRS 3.2.5; D-53, D-64.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class RiskProfileMigrationTest {

    private static final String V12 = "db/migration/V12__user_profile_risk_profile.sql";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    /** The seeded administrator's profile existed before V12, and was given the default. */
    @Test
    void D64_aProfileThatExistedBeforeTheMigration_isConservative() {
        assertThat(jdbc.sql("select distinct risk_profile from user_profile")
                        .query(String.class)
                        .list())
                .containsExactly("CONSERVATIVE");
    }

    @Test
    void D64_aProfileInsertedWithoutARiskProfile_isConservative() {
        UUID id = profile();

        assertThat(riskProfileOf(id)).isEqualTo("CONSERVATIVE");
    }

    @Test
    void BR66_aValueOutsideTheThreeProfiles_isRefused() {
        UUID id = profile();

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.sql("update user_profile set risk_profile = 'RECKLESS' where user_id = ?")
                        .param(id)
                        .update())
                .withStackTraceContaining("ck_user_profile_risk_profile");
    }

    @Test
    void BR66_aProfileWithoutARiskProfile_isRefused() {
        UUID id = profile();

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.sql("update user_profile set risk_profile = null where user_id = ?")
                        .param(id)
                        .update());
    }

    /** Re-running V12 keeps every chosen profile and adds no second constraint. */
    @Test
    void BR66_runningTheMigrationAgain_changesNothing() throws Exception {
        UUID id = profile();
        jdbc.sql("update user_profile set risk_profile = 'AGGRESSIVE' where user_id = ?")
                .param(id)
                .update();
        List<Map<String, Object>> before = jdbc.sql("select user_id, risk_profile from user_profile order by user_id")
                .query()
                .listOfRows();

        String sql = new String(
                FileCopyUtils.copyToByteArray(new ClassPathResource(V12).getInputStream()), StandardCharsets.UTF_8);
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(
                            new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8), V12), StandardCharsets.UTF_8),
                    false,
                    false,
                    ScriptUtils.DEFAULT_COMMENT_PREFIX,
                    ScriptUtils.EOF_STATEMENT_SEPARATOR,
                    ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
                    ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }

        assertThat(jdbc.sql("select user_id, risk_profile from user_profile order by user_id")
                        .query()
                        .listOfRows())
                .isEqualTo(before);
        assertThat(jdbc.sql("select count(*) from pg_constraint where conname = 'ck_user_profile_risk_profile'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    private UUID profile() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-09-28T00:00:00Z"));
        jdbc.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@t103.invalid", now, now).update();
        jdbc.sql("""
                        insert into user_profile (user_id, display_name, created_at, updated_at)
                        values (?, 'Trader', ?, ?)""").params(id, now, now).update();
        return id;
    }

    private String riskProfileOf(UUID id) {
        return jdbc.sql("select risk_profile from user_profile where user_id = ?")
                .param(id)
                .query(String.class)
                .single();
    }
}
