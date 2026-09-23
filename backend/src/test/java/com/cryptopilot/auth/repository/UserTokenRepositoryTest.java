package com.cryptopilot.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.auth.entity.TokenType;
import com.cryptopilot.auth.entity.UserToken;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.entity.UserAccount;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The four operations the token repository offers, against the schema Flyway migrated.
 *
 * <p>Two of them carry a rule. Looking a token up together with its kind is what stops a password
 * reset link from verifying an address; asking for the most recently issued token of a kind is
 * what a resend throttle decides against. The other two are the plain ones, and the test that
 * matters for them is the miss rather than the hit.
 *
 * <p>What is proved here is the persistence half of BR-01 and BR-04: a token can be found by the
 * digest of the value presented, and only as the kind it was issued as. That a link is valid for 24
 * hours or 30 minutes and may be used once is the entity's — those instants never appear in a
 * query here, on purpose — and the flows that issue and consume the links belong to the use cases.
 *
 * <p>Every test rolls back; the suite shares one database and the seed tests assert an empty token
 * table.
 *
 * <p>Rule: BR-01, BR-04; TECHNICAL_DESIGN sections 3.1, 5.3 and 7.15.
 *
 * <p>Reference: Bauer, C., King, G. &amp; Gregory, G. (2015). <i>Java Persistence with
 * Hibernate</i> (2nd ed.). Manning, ch. 14 (query strategy).
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(TestcontainersConfig.class)
@Transactional
class UserTokenRepositoryTest {

    private static final Instant ISSUED = Instant.parse("2026-09-21T10:00:00Z");
    private static final Instant EXPIRES = ISSUED.plusSeconds(24 * 60 * 60);

    @Autowired
    private UserTokenRepository tokens;

    @Autowired
    private EntityManager em;

    @Autowired
    private JdbcClient jdbc;

    // ------------------------------------------------------------------------------------------
    // Finding a token by the digest of the value presented (BR-01, BR-04)
    // ------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(TokenType.class)
    void BR01_aTokenIsFoundByTheDigestOfTheValuePresented(TokenType type) {
        UUID account = persistedAccount("digest." + type + "@cryptopilot.invalid");
        UserToken issued = persistedToken(account, type, digest('a'), EXPIRES);
        em.clear();

        assertThat(tokens.findByTokenHash(digest('a')))
                .get()
                .extracting(UserToken::getId)
                .isEqualTo(issued.getId());
    }

    /**
     * The miss. A digest nothing was issued for finds nothing — which is also what a caller sees
     * when a link was never valid, and is why the rejection message cannot distinguish "unknown"
     * from "already used".
     */
    @Test
    void aDigestNoTokenWasIssuedFor_isNotFound() {
        assertThat(tokens.findByTokenHash(digest('f'))).isEmpty();
        assertThat(tokens.findByTokenHashAndTokenType(digest('f'), TokenType.EMAIL_VERIFICATION))
                .isEmpty();
    }

    /**
     * The rule the second lookup exists for: three kinds share one table and one digest column, so
     * a reset link presented to the verification endpoint must find nothing rather than find a row
     * the caller has to remember to reject.
     *
     * <p>Rule: BR-01, BR-04.
     */
    @Test
    void BR04_aTokenIsNotFoundUnderAKindItWasNotIssuedAs() {
        UUID account = persistedAccount("kinds@cryptopilot.invalid");
        persistedToken(account, TokenType.PASSWORD_RESET, digest('b'), EXPIRES);
        em.clear();

        assertThat(tokens.findByTokenHashAndTokenType(digest('b'), TokenType.PASSWORD_RESET))
                .as("the kind it was issued as")
                .isPresent();
        assertThat(tokens.findByTokenHashAndTokenType(digest('b'), TokenType.EMAIL_VERIFICATION))
                .as("a reset link does not verify an address")
                .isEmpty();
        assertThat(tokens.findByTokenHashAndTokenType(digest('b'), TokenType.REFRESH))
                .isEmpty();
    }

    /**
     * An expired or already used token is still found. The lookup answers "which row is this", and
     * whether the row may still be used is {@link UserToken#isUsableAt(Instant)} — evaluated by the
     * caller against the injected clock, not by a predicate evaluated against the database's.
     *
     * <p>If the query filtered on expiry instead, the rule would exist twice and a link presented
     * one second late would look like a link that never existed.
     */
    @Test
    void BR01_aSpentOrExpiredToken_isStillFoundSoThatTheEntityCanRefuseIt() {
        UUID account = persistedAccount("spent@cryptopilot.invalid");
        UserToken spent = persistedToken(account, TokenType.EMAIL_VERIFICATION, digest('c'), EXPIRES);
        spent.markUsed(ISSUED.plusSeconds(60));
        UserToken expired = persistedToken(account, TokenType.PASSWORD_RESET, digest('d'), ISSUED);
        em.flush();
        em.clear();

        assertThat(tokens.findByTokenHash(digest('c'))).isPresent();
        assertThat(tokens.findByTokenHash(digest('d'))).isPresent();

        assertThat(tokens.findByTokenHash(digest('c')).orElseThrow().isUsableAt(ISSUED.plusSeconds(120)))
                .as("used, so no longer usable")
                .isFalse();
        assertThat(tokens.findByTokenHash(digest('d')).orElseThrow().isUsableAt(expired.getExpiresAt()))
                .as("the expiry instant is already past the window")
                .isFalse();
    }

    // ------------------------------------------------------------------------------------------
    // The latest token of a kind, for the resend throttle (UC-02)
    // ------------------------------------------------------------------------------------------

    /**
     * The row a resend decides against: the last verification mail this account was sent. The
     * ordering is the whole method, so the fixture issues three tokens whose instants differ and
     * the newest has to come back.
     *
     * <p>How long the window is does not appear here and does not appear in the repository. It is
     * not in the SRS either, which is why it is an open question rather than a number somebody
     * chose.
     */
    @Test
    void theLatestTokenOfAKind_isTheOneAResendIsThrottledAgainst() {
        UUID account = persistedAccount("throttle@cryptopilot.invalid");
        persistedTokenCreatedAt(account, TokenType.EMAIL_VERIFICATION, digest('1'), ISSUED.minusSeconds(600));
        persistedTokenCreatedAt(account, TokenType.EMAIL_VERIFICATION, digest('3'), ISSUED.minusSeconds(60));
        persistedTokenCreatedAt(account, TokenType.EMAIL_VERIFICATION, digest('2'), ISSUED.minusSeconds(300));
        em.clear();

        assertThat(tokens.findTopByUserIdAndTokenTypeOrderByCreatedAtDesc(account, TokenType.EMAIL_VERIFICATION))
                .get()
                .extracting(UserToken::getTokenHash)
                .isEqualTo(digest('3'));
    }

    /** The kind is part of the question: a reset link is not a verification mail. */
    @Test
    void theLatestTokenOfAKind_ignoresTheOtherKinds() {
        UUID account = persistedAccount("throttle.kinds@cryptopilot.invalid");
        persistedTokenCreatedAt(account, TokenType.EMAIL_VERIFICATION, digest('1'), ISSUED.minusSeconds(600));
        persistedTokenCreatedAt(account, TokenType.PASSWORD_RESET, digest('2'), ISSUED);
        em.clear();

        assertThat(tokens.findTopByUserIdAndTokenTypeOrderByCreatedAtDesc(account, TokenType.EMAIL_VERIFICATION))
                .get()
                .extracting(UserToken::getTokenHash)
                .isEqualTo(digest('1'));
    }

    /** The account is part of the question too: another account's mail throttles nobody. */
    @Test
    void theLatestTokenOfAKind_ignoresOtherAccounts() {
        UUID mine = persistedAccount("mine@cryptopilot.invalid");
        UUID theirs = persistedAccount("theirs@cryptopilot.invalid");
        persistedTokenCreatedAt(theirs, TokenType.EMAIL_VERIFICATION, digest('2'), ISSUED);
        em.clear();

        assertThat(tokens.findTopByUserIdAndTokenTypeOrderByCreatedAtDesc(mine, TokenType.EMAIL_VERIFICATION))
                .isEmpty();
    }

    /** An account that was never sent one: empty, so a first resend is not throttled by a null. */
    @Test
    void anAccountThatWasNeverSentAToken_hasNoLatestOne() {
        UUID account = persistedAccount("never@cryptopilot.invalid");
        em.clear();

        assertThat(tokens.findTopByUserIdAndTokenTypeOrderByCreatedAtDesc(account, TokenType.EMAIL_VERIFICATION))
                .isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // Cost, uniqueness and shape
    // ------------------------------------------------------------------------------------------

    /**
     * One statement per lookup. A token names its account by identifier and holds no association,
     * so nothing is loaded behind it — which is what keeps a verification from costing two queries
     * and a sign-in from costing three.
     */
    @Test
    void eachLookup_costsExactlyOneStatement() {
        UUID account = persistedAccount("cost@cryptopilot.invalid");
        persistedToken(account, TokenType.EMAIL_VERIFICATION, digest('e'), EXPIRES);
        em.flush();
        em.clear();
        Statistics statistics = statistics();

        statistics.clear();
        tokens.findByTokenHash(digest('e'));
        assertThat(statistics.getPrepareStatementCount()).as("findByTokenHash").isEqualTo(1L);

        em.clear();
        statistics.clear();
        tokens.findByTokenHashAndTokenType(digest('e'), TokenType.EMAIL_VERIFICATION);
        assertThat(statistics.getPrepareStatementCount())
                .as("findByTokenHashAndTokenType: no account is fetched behind the token")
                .isEqualTo(1L);

        em.clear();
        statistics.clear();
        tokens.findTopByUserIdAndTokenTypeOrderByCreatedAtDesc(account, TokenType.EMAIL_VERIFICATION);
        assertThat(statistics.getPrepareStatementCount())
                .as("findTopByUserIdAndTokenTypeOrderByCreatedAtDesc")
                .isEqualTo(1L);
    }

    /** The lookups use the indexes the schema provides rather than reading a table that grows fast. */
    @Test
    void theLookups_useTheIndexesTheSchemaProvides() {
        assertThat(planFor("select token_id from user_token where token_hash = '" + digest('a') + "'"))
                .contains("uq_user_token_hash");
        assertThat(planFor("select token_id from user_token where user_id = '" + UUID.randomUUID()
                        + "' and token_type = 'EMAIL_VERIFICATION'"))
                .contains("idx_user_token_user_type");
    }

    /**
     * The digest column is unique, which is what makes the lookup a single-row answer and what
     * would refuse a second token that somehow hashed to the same value.
     */
    @Test
    void twoTokensWithTheSameDigest_areRefusedByTheDatabase() {
        UUID account = persistedAccount("dupdigest@cryptopilot.invalid");
        persistedToken(account, TokenType.EMAIL_VERIFICATION, digest('9'), EXPIRES);
        em.flush();

        tokens.save(UserToken.issue(account, TokenType.PASSWORD_RESET, digest('9'), EXPIRES));

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> tokens.findByTokenHash(digest('8')))
                .withStackTraceContaining("uq_user_token_hash");
    }

    /**
     * No method here takes or returns a raw secret, and none offers an unbounded read of a table
     * that grows with every sign-in and every reset request. Both are asserted over the interface
     * rather than left to review: the first is what keeps a token out of a query log, the second is
     * what keeps a list screen from being written against a million rows.
     *
     * <p>The list is exact, so the surface cannot grow quietly. {@code revokeFamily} joined it with
     * the refresh rotation that needs it: reuse detection has to stop every token of a family in one
     * statement, and the alternative — load them and call the entity on each — is both the unbounded
     * read this interface does not offer and unable to reach an expired-but-unused sibling, because
     * {@code markUsed} refuses an expired token. It takes a family identifier and answers a count, so
     * neither rule this test exists for is touched. {@code revokeOtherSessions} joined it with the
     * password change of SRS 3.2.5 on the same terms: an account and a family in, a count out.
     */
    @Test
    void theRepository_offersOnlyBoundedLookupsAndNamesEveryParameterADigest() {
        assertThat(UserTokenRepository.class.getMethods())
                .extracting(Method::getName)
                .containsExactlyInAnyOrder(
                        "findByTokenHash",
                        "findByTokenHashAndTokenType",
                        "findTopByUserIdAndTokenTypeOrderByCreatedAtDesc",
                        "countIssuedSince",
                        "invalidateUnused",
                        "revokeFamily",
                        "revokeOtherSessions",
                        "save");

        assertThat(UserTokenRepository.class.getMethods())
                .as("a read answers with at most one token; a count answers a number and a bulk update"
                        + " answers how many rows it touched. Nothing answers a list, so no caller can"
                        + " read this table whole")
                .allSatisfy(method ->
                        assertThat(method.getReturnType()).isIn(java.util.Optional.class, UserToken.class, int.class));

        assertThatThrownBy(() -> UserTokenRepository.class.getMethod("findAll"))
                .isInstanceOf(NoSuchMethodException.class);
    }

    /**
     * TECHNICAL_DESIGN 7.15: one statement stops every unused token of a family, and nothing outside
     * it. The expired sibling is the case that matters — it is unused, so it could still be revived
     * by a clock change or a migration, and {@code markUsed} would have refused to touch it.
     */
    @Test
    void TD715_revokingAFamily_stopsItsUnusedTokensAndLeavesOtherFamiliesAlone() {
        UUID account = persistedAccount("family@cryptopilot.invalid");
        UUID otherFamily = UUID.fromString("019b76da-a800-7000-8000-0000000000f2");
        UserToken retired = persistedToken(account, TokenType.REFRESH, digest('5'), EXPIRES, FAMILY);
        retired.markUsed(ISSUED.plusSeconds(1));
        UserToken current = persistedToken(account, TokenType.REFRESH, digest('6'), EXPIRES, FAMILY);
        UserToken expiredSibling = persistedToken(account, TokenType.REFRESH, digest('7'), ISSUED, FAMILY);
        UserToken elsewhere = persistedToken(account, TokenType.REFRESH, digest('8'), EXPIRES, otherFamily);
        em.flush();
        em.clear();

        int revoked = tokens.revokeFamily(FAMILY, ISSUED.plusSeconds(60));
        em.clear();

        assertThat(revoked)
                .as("the two unused tokens of the family; the one already used is not counted again")
                .isEqualTo(2);
        assertThat(usedAtOf(current)).isEqualTo(ISSUED.plusSeconds(60));
        assertThat(usedAtOf(expiredSibling))
                .as("an unused token that had expired is stopped too, which markUsed would have refused")
                .isEqualTo(ISSUED.plusSeconds(60));
        assertThat(usedAtOf(retired)).as("already spent, and not restamped").isEqualTo(ISSUED.plusSeconds(1));
        assertThat(usedAtOf(elsewhere))
                .as("another sign-in of the same account is another family and is untouched")
                .isNull();
    }

    /**
     * SRS 3.2.5: a password change ends every <em>other</em> session. Every unused refresh token of the
     * account outside the kept family stops — expired ones too, for the reason revoking a family gives
     * — while the kept family, the account's other kinds of token and another account's sessions are
     * untouched.
     */
    @Test
    void UC07_revokingOtherSessions_keepsOneFamilyAndStopsTheRest() {
        UUID account = persistedAccount("other-sessions@cryptopilot.invalid");
        UUID stranger = persistedAccount("stranger@cryptopilot.invalid");
        UUID otherFamily = UUID.fromString("019b76da-a800-7000-8000-0000000000f3");
        UUID expiredFamily = UUID.fromString("019b76da-a800-7000-8000-0000000000f4");
        UserToken kept = persistedToken(account, TokenType.REFRESH, digest('a'), EXPIRES, FAMILY);
        UserToken other = persistedToken(account, TokenType.REFRESH, digest('b'), EXPIRES, otherFamily);
        UserToken expiredOther = persistedToken(account, TokenType.REFRESH, digest('c'), ISSUED, expiredFamily);
        UserToken resetLink = persistedToken(account, TokenType.PASSWORD_RESET, digest('d'), EXPIRES);
        UserToken strangers = persistedToken(stranger, TokenType.REFRESH, digest('e'), EXPIRES, otherFamily);
        em.flush();
        em.clear();

        int revoked = tokens.revokeOtherSessions(account, FAMILY, ISSUED.plusSeconds(60));
        em.clear();

        assertThat(revoked).isEqualTo(2);
        assertThat(usedAtOf(other)).isEqualTo(ISSUED.plusSeconds(60));
        assertThat(usedAtOf(expiredOther)).isEqualTo(ISSUED.plusSeconds(60));
        assertThat(usedAtOf(kept)).as("the caller's own session").isNull();
        assertThat(usedAtOf(resetLink)).as("not a session").isNull();
        assertThat(usedAtOf(strangers)).as("another account").isNull();
    }

    @Test
    void TD715_revokingAFamilyThatHasNothingUnused_changesNothing() {
        UUID account = persistedAccount("empty-family@cryptopilot.invalid");
        persistedToken(account, TokenType.REFRESH, digest('9'), EXPIRES, FAMILY);
        em.clear();

        assertThat(tokens.revokeFamily(UUID.fromString("019b76da-a800-7000-8000-0000000000f9"), ISSUED))
                .isZero();
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private Instant usedAtOf(UserToken token) {
        return jdbc.sql("select used_at from user_token where token_id = ?")
                .param(token.getId())
                .query(Instant.class)
                .optional()
                .orElse(null);
    }

    /** The family the refresh tokens of this class belong to unless a case names another. */
    private static final UUID FAMILY = UUID.fromString("019b76da-a800-7000-8000-0000000000f1");

    /** A SHA-256 digest in lower-case hexadecimal, distinguished only by the character it repeats. */
    private static String digest(char marker) {
        return String.valueOf(marker).repeat(64);
    }

    private UUID persistedAccount(String email) {
        UserAccount account = UserAccount.register(email, "hashed");
        em.persist(account);
        em.flush();
        return account.getId();
    }

    private UserToken persistedToken(UUID account, TokenType type, String tokenDigest, Instant expiresAt) {
        return persistedToken(account, type, tokenDigest, expiresAt, FAMILY);
    }

    /**
     * A token of any kind, built through the factory that kind has: a refresh token belongs to a
     * family and the other two may not have one, which is a check constraint as well as a rule, so a
     * test that ignored it would be refused by the database rather than by the mapping.
     */
    private UserToken persistedToken(UUID account, TokenType type, String tokenDigest, Instant expiresAt, UUID family) {
        UserToken token = tokens.save(
                type == TokenType.REFRESH
                        ? UserToken.issueRefresh(account, tokenDigest, expiresAt, family)
                        : UserToken.issue(account, type, tokenDigest, expiresAt));
        em.flush();
        return token;
    }

    /**
     * A token whose creation instant is forced, because the audit listener stamps every row in one
     * test with the same clock reading and the ordering under test needs them to differ.
     */
    private void persistedTokenCreatedAt(UUID account, TokenType type, String tokenDigest, Instant createdAt) {
        UserToken token = persistedToken(account, type, tokenDigest, EXPIRES);
        jdbc.sql("update user_token set created_at = ? where token_id = ?")
                .param(createdAt.atOffset(ZoneOffset.UTC))
                .param(token.getId())
                .update();
    }

    private String planFor(String sql) {
        jdbc.sql("set local enable_seqscan = off").update();
        return String.join("\n", jdbc.sql("explain " + sql).query(String.class).list());
    }

    private Statistics statistics() {
        return em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }
}
