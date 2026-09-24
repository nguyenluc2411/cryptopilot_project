package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.support.TestcontainersConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The column V6 adds, pinned against the database the migration produced: a counter that starts at
 * zero and cannot go below it. {@link SchemaColumnContractTest} already fails on a column added or
 * removed; this class asserts what a column list cannot — the default and the check.
 *
 * <p>Every test rolls back, as in {@link LoginStateMigrationTest}.
 *
 * <p>Rule: SRS 3.2.5, UC-07; A-30.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class PasswordChangeCounterMigrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void A30_theCounter_isANonNullIntegerStartingAtZero() {
        Map<String, Object> actual = jdbc.sql("""
                        select data_type, is_nullable, column_default
                          from information_schema.columns
                         where table_schema = 'public' and table_name = 'user_account'
                           and column_name = 'failed_password_change_count'""").query().singleRow();

        assertThat(actual.get("data_type")).isEqualTo("integer");
        assertThat(actual.get("is_nullable")).isEqualTo("NO");
        assertThat(String.valueOf(actual.get("column_default"))).startsWith("0");
    }

    @Test
    void A30_aNegativeCount_isRefusedByTheDatabase() {
        UUID account = jdbc.sql("select user_id from user_account order by created_at limit 1")
                .query(UUID.class)
                .single();

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(
                        () -> jdbc.sql("update user_account set failed_password_change_count = -1 where user_id = ?")
                                .param(account)
                                .update())
                .withStackTraceContaining("ck_user_account_failed_password_change_count");
    }
}
