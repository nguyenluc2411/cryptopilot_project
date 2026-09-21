package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.support.TestcontainersConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three columns of A-10, pinned against the database the migration produced.
 *
 * <p>{@link BaselineSchemaTest} pins what V1 created and is deliberately left alone: these columns
 * arrived in a later, additive migration, and keeping their assertions here says which migration is
 * being asserted. {@link SchemaColumnContractTest} already fails on any column added or removed, so
 * what is left for this class is what a column list cannot express — the default a counter starts
 * at, the index the family revocation runs over, and the invariant V5 adds once the mapping can
 * satisfy it.
 *
 * <p>Every test rolls back. The suite shares one database and the seed tests next door assert that
 * exactly one account exists, so a row written here has to disappear again.
 *
 * <p>Rule: BR-03, A-10; TECHNICAL_DESIGN sections 6 and 7.15.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class LoginStateMigrationTest {

    @Autowired
    private JdbcClient jdbc;

    @ParameterizedTest(name = "{0}.{1} is {2}, nullable={3}, default={4}")
    @CsvSource({
        "user_account,failed_login_count,integer,NO,0",
        "user_account,locked_until,timestamp with time zone,YES,",
        "user_token,token_family_id,uuid,YES,"
    })
    void A10_theLoginStateColumns_existWithTheShapeTheRulesNeed(
            String table, String column, String type, String nullable, String columnDefault) {
        Map<String, Object> actual = jdbc.sql("""
                        select data_type, is_nullable, coalesce(column_default, '') as column_default
                          from information_schema.columns
                         where table_schema = 'public' and table_name = ? and column_name = ?""").params(table, column).query().singleRow();

        assertThat(actual.get("data_type")).isEqualTo(type);
        assertThat(actual.get("is_nullable")).isEqualTo(nullable);
        assertThat(String.valueOf(actual.get("column_default")))
                .as("a counter that starts at nothing would make the first failed attempt the fifth")
                .startsWith(columnDefault == null ? "" : columnDefault);
    }

    /**
     * BR-03 counts attempts, and a count is never negative. The constraint is asserted by being
     * broken: a check nobody has violated in a test is a check nobody has verified.
     */
    @Test
    void BR03_aNegativeFailedLoginCount_isRefusedByTheDatabase() {
        UUID account = seededAdministrator();

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.sql("update user_account set failed_login_count = -1 where user_id = ?")
                        .param(account)
                        .update())
                .withStackTraceContaining("ck_user_account_failed_login_count");
    }

    /**
     * A family belongs to a refresh token and to nothing else (V5). Both directions are refused, which
     * is the point of writing the constraint as an equality between two predicates: a refresh token
     * with no family cannot be revoked with its siblings, and a verification or reset link that was
     * given one would be swept away by a revocation that has nothing to do with it.
     *
     * <p>The entity refuses both too, through two factory methods that cannot do each other's job.
     * This is the database saying the same thing, so that a bulk insert, a data fix or a future module
     * cannot write a row the rules do not allow.
     */
    @ParameterizedTest(name = "{0} with a family: {1} is refused")
    @CsvSource({"REFRESH,false", "EMAIL_VERIFICATION,true", "PASSWORD_RESET,true"})
    void TD715_onlyARefreshToken_carriesAFamily(String tokenType, boolean withFamily) {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertToken(tokenType, withFamily ? UUID.randomUUID() : null))
                .withStackTraceContaining("ck_user_token_family");
    }

    @ParameterizedTest(name = "{0} with a family: {1} is accepted")
    @CsvSource({"REFRESH,true", "EMAIL_VERIFICATION,false", "PASSWORD_RESET,false"})
    void TD715_theCombinationsTheRuleAllows_areAccepted(String tokenType, boolean withFamily) {
        assertThat(insertToken(tokenType, withFamily ? UUID.randomUUID() : null))
                .isEqualTo(1);
    }

    /**
     * Revoking a family is one statement, and it runs on the path where somebody is holding a
     * copied token. Without this index it would scan a table that grows with every sign-in.
     */
    @Test
    void TD715_theFamilyIndex_exists() {
        Integer count = jdbc.sql("select count(*) from pg_indexes where schemaname = 'public' and indexname = ?")
                .param("idx_user_token_family")
                .query(Integer.class)
                .single();

        assertThat(count).isEqualTo(1);
    }

    private int insertToken(String tokenType, UUID family) {
        return jdbc.sql("""
                        insert into user_token
                            (token_id, user_id, token_type, token_hash, expires_at, token_family_id,
                             created_at, updated_at, version)
                        values (?, ?, ?, ?, now(), ?, now(), now(), 0)""")
                .params(UUID.randomUUID(), seededAdministrator(), tokenType, "b".repeat(64), family)
                .update();
    }

    private UUID seededAdministrator() {
        return jdbc.sql("select user_id from user_account order by created_at limit 1")
                .query(UUID.class)
                .single();
    }
}
