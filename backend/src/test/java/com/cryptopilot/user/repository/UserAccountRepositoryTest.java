package com.cryptopilot.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.entity.AccountStatus;
import com.cryptopilot.user.entity.Role;
import com.cryptopilot.user.entity.UserAccount;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The four operations the account repository offers, against the schema Flyway migrated.
 *
 * <p>Three kinds of assertion. That each query finds what it should and misses what it should not;
 * that the case-insensitive lookups use the functional index the schema provides rather than
 * reading the table; and that uniqueness is the database's job, so a duplicate that slips past the
 * existence check is still refused.
 *
 * <p>What is proved here is the persistence half of BR-01 — that an address identifies exactly one
 * account, whatever case it is written in. The registration flow that turns a duplicate into MSG04,
 * and the verification mail, are the rest of the rule and belong to the use case itself.
 *
 * <p>Every test rolls back. The suite shares one database and the seed tests assert that exactly
 * one account exists, so nothing here may survive its own test.
 *
 * <p>Rule: BR-01, BR-05; TECHNICAL_DESIGN sections 3.1 and 6.
 *
 * <p>Reference: Bauer, C., King, G. &amp; Gregory, G. (2015). <i>Java Persistence with
 * Hibernate</i> (2nd ed.). Manning, ch. 14 and 15 (query strategy, and reading the plan rather than
 * assuming it).
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(TestcontainersConfig.class)
@Transactional
class UserAccountRepositoryTest {

    /** The administrator V3 seeded, by the literal key the migration wrote. */
    private static final UUID SEEDED_ADMIN = UUID.fromString("019b76da-a800-7000-8000-000000000001");

    private static final String SEEDED_ADMIN_EMAIL = "admin@cryptopilot.invalid";

    /** The instant V3 stamps on every seeded row. */
    private static final Instant SEED_EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private EntityManager em;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // ------------------------------------------------------------------------------------------
    // Finding an account by its address (BR-01)
    // ------------------------------------------------------------------------------------------

    /**
     * The hit, in every case the address could be typed in. BR-01 gives one account per address,
     * and the schema makes that hold case-insensitively, so all four spellings are one account.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "admin@cryptopilot.invalid",
                "ADMIN@CRYPTOPILOT.INVALID",
                "Admin@CryptoPilot.Invalid",
                "aDmIn@cRyPtOpIlOt.InVaLiD"
            })
    void BR01_theAccountHoldingAnAddress_isFoundHoweverTheAddressIsCased(String spelling) {
        assertThat(accounts.findByEmailIgnoringCase(spelling))
                .get()
                .extracting(UserAccount::getId)
                .isEqualTo(SEEDED_ADMIN);
        assertThat(accounts.existsByEmailIgnoringCase(spelling)).isTrue();
    }

    /** The miss: empty, never null, so "no such account" is a value the caller has to handle. */
    @Test
    void anAddressNoAccountHolds_isNotFound() {
        assertThat(accounts.findByEmailIgnoringCase("nobody@cryptopilot.invalid"))
                .isEmpty();
        assertThat(accounts.existsByEmailIgnoringCase("nobody@cryptopilot.invalid"))
                .isFalse();
    }

    /**
     * The boundary between "ignores case" and "ignores anything else". Case is the only difference
     * the lookup forgives: an address that merely looks similar is a different address. Trimming,
     * in particular, is the caller's — an address is not normalised on its way into this query.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                " admin@cryptopilot.invalid",
                "admin@cryptopilot.invalid ",
                "admin@cryptopilot.invali",
                "admin+tag@cryptopilot.invalid",
                "adm in@cryptopilot.invalid"
            })
    void anAddressThatDiffersByMoreThanCase_isADifferentAccount(String notTheAddress) {
        assertThat(accounts.findByEmailIgnoringCase(notTheAddress)).isEmpty();
        assertThat(accounts.existsByEmailIgnoringCase(notTheAddress)).isFalse();
    }

    /**
     * The row the seed migration wrote, reached through the repository with the values it wrote.
     * The seed ran long before this interface existed, so finding it here by address and by key is
     * what shows the query matches the data rather than matching the test's own fixture.
     *
     * <p>This is the bootstrap half of BR-05 — that an ADMIN exists and can be found. That only an
     * administrator may grant the role is authorization and belongs to T-015.
     */
    @Test
    void BR05_theSeededAdministrator_isReachableByAddressAndByKeyWithTheValuesTheSeedWrote() {
        UserAccount byAddress =
                accounts.findByEmailIgnoringCase(SEEDED_ADMIN_EMAIL).orElseThrow();
        UserAccount byKey = accounts.findById(SEEDED_ADMIN).orElseThrow();

        assertThat(byAddress).isEqualTo(byKey);
        assertThat(byKey.getEmail()).isEqualTo(SEEDED_ADMIN_EMAIL);
        assertThat(byKey.getRole()).isEqualTo(Role.ADMIN);
        assertThat(byKey.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(byKey.getEmailVerifiedAt()).isEqualTo(SEED_EPOCH);
        assertThat(byKey.getCreatedAt()).isEqualTo(SEED_EPOCH);
        assertThat(byKey.getVersion()).isZero();
    }

    @Test
    void anIdentifierNoAccountHolds_isNotFound() {
        assertThat(accounts.findById(UUID.fromString("019b76da-a800-7000-8000-0000000000ff")))
                .isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // The index behind the lookups
    // ------------------------------------------------------------------------------------------

    /**
     * The reason the two lookups are written as JPQL instead of being derived. The schema indexes
     * {@code lower(email)}; Spring Data's {@code IgnoreCase} keyword would have generated
     * {@code upper(email) = upper(?)}, which is just as correct and which no index in this schema
     * covers — a query that would have been right and slow, and slow in a way no functional test
     * would ever have shown.
     *
     * <p>Sequential scans are penalised for the plan so that the question is the one worth asking —
     * can this predicate use that index — rather than what the planner prefers on a table holding
     * one row. The contrast is the assertion: the same query written with {@code upper} cannot use
     * it whatever the planner is told.
     */
    @Test
    void theCaseInsensitiveLookup_usesTheFunctionalIndexTheSchemaProvides() {
        assertThat(planFor("select user_id from user_account where lower(email) = lower('x@y.invalid')"))
                .contains("uq_user_account_email_lower");

        assertThat(planFor("select user_id from user_account where upper(email) = upper('x@y.invalid')"))
                .as("the predicate a derived IgnoreCase query would have produced")
                .doesNotContain("uq_user_account_email_lower");
    }

    /** One statement per lookup: nothing is loaded behind an account. */
    @Test
    void eachLookup_costsExactlyOneStatement() {
        em.flush();
        em.clear();
        Statistics statistics = statistics();

        statistics.clear();
        accounts.findByEmailIgnoringCase(SEEDED_ADMIN_EMAIL);
        assertThat(statistics.getPrepareStatementCount())
                .as("findByEmailIgnoringCase")
                .isEqualTo(1L);

        statistics.clear();
        accounts.existsByEmailIgnoringCase(SEEDED_ADMIN_EMAIL);
        assertThat(statistics.getPrepareStatementCount())
                .as("existsByEmailIgnoringCase")
                .isEqualTo(1L);

        em.clear();
        statistics.clear();
        accounts.findById(SEEDED_ADMIN);
        assertThat(statistics.getPrepareStatementCount())
                .as("findById: no profile, no device and no token is fetched behind the account")
                .isEqualTo(1L);
    }

    // ------------------------------------------------------------------------------------------
    // Writing, and losing the race (BR-01)
    // ------------------------------------------------------------------------------------------

    @Test
    void anAccountThatIsSaved_isFoundAgainByItsAddress() {
        UserAccount saved = accounts.save(UserAccount.register("new.trader@cryptopilot.invalid", "hashed"));
        em.flush();
        em.clear();

        assertThat(accounts.findByEmailIgnoringCase("NEW.TRADER@cryptopilot.invalid"))
                .get()
                .extracting(UserAccount::getId)
                .isEqualTo(saved.getId());
    }

    /**
     * The existence check is an optimisation, not the guarantee. This is what a caller that skipped
     * it — or that passed it and then lost the race — runs into: the functional unique index
     * refuses the second row, whatever case it is written in.
     *
     * <p>It surfaces as Spring's persistence-neutral {@link DataIntegrityViolationException},
     * because the flush happens while a repository method is running and the repository proxy
     * translates it. That the web boundary then answers a conflict rather than a crash is asserted
     * where the handler lives.
     *
     * <p>Rule: BR-01 (one account per address).
     */
    @Test
    void BR01_aSecondAccountForTheSameAddress_isRefusedByTheDatabaseWhateverItsCase() {
        assertThat(accounts.existsByEmailIgnoringCase(SEEDED_ADMIN_EMAIL))
                .as("the check a careful caller makes first")
                .isTrue();

        accounts.save(UserAccount.register("ADMIN@CRYPTOPILOT.INVALID", "hashed"));

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .as("the query flushes the pending insert before it runs, and the index refuses it")
                .isThrownBy(() -> accounts.findByEmailIgnoringCase("anyone@cryptopilot.invalid"))
                .withStackTraceContaining("uq_user_account_email");
    }

    // ------------------------------------------------------------------------------------------
    // Transaction semantics
    // ------------------------------------------------------------------------------------------

    /**
     * A read-only transaction cannot write. Hibernate leaves the persistence context unflushed, so
     * a change made on a loaded entity never reaches the database — which is what makes marking the
     * lookups read-only a guard rather than a decoration.
     *
     * <p>It runs in a transaction of its own. Joining the read-write one this class opens would
     * silently inherit that, and the test would assert nothing at all.
     */
    @Test
    void aReadOnlyPath_cannotWriteThroughAnEntityItLoaded() {
        String storedBefore = committedRoleOfSeededAdmin();

        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        readOnly.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        readOnly.executeWithoutResult(
                status -> accounts.findById(SEEDED_ADMIN).orElseThrow().changeRole(Role.TRADER));

        assertThat(committedRoleOfSeededAdmin())
                .as("the change was discarded with the persistence context instead of being flushed")
                .isEqualTo(storedBefore)
                .isEqualTo("ADMIN");
    }

    /**
     * Nothing this class writes survives it. Stated as an assertion rather than left to the
     * framework, because the seed tests assert that exactly one account exists and they would be
     * the ones to fail — long after the test that actually leaked the row.
     */
    @Test
    void aWriteThatIsNotCommitted_isInvisibleOutsideItsTransaction() {
        accounts.save(UserAccount.register("rolled.back@cryptopilot.invalid", "hashed"));
        em.flush();

        assertThat(countOfAccountsInThisTransaction())
                .as("two while this transaction is open: the seeded administrator and the new row")
                .isEqualTo(2);
        assertThat(committedAccountCount())
                .as("one on another connection, because this transaction will never commit")
                .isEqualTo(1);
    }

    /**
     * The other half of the same guarantee: that the mechanism above is actually applied. The test
     * before it proves a read-only transaction discards a write, but it opens that transaction
     * itself — so it would go on passing if every annotation here were deleted.
     *
     * <p>Stated over the interface, because that is where the decision lives: each read declares
     * itself read-only, and {@code save} deliberately declares nothing, so it joins the caller's
     * transaction and an account and its profile can be written as one unit of work.
     */
    @Test
    void everyReadDeclaresItselfReadOnly_andTheWriteLeavesTheTransactionToItsCaller() throws Exception {
        assertThat(readOnlyFlagOf("findById", UUID.class)).isTrue();
        assertThat(readOnlyFlagOf("findByEmailIgnoringCase", String.class)).isTrue();
        assertThat(readOnlyFlagOf("existsByEmailIgnoringCase", String.class)).isTrue();

        assertThat(UserAccountRepository.class
                        .getMethod("save", UserAccount.class)
                        .getAnnotation(Transactional.class))
                .as("the unit of work spans the account and its profile, so it is the caller's")
                .isNull();
    }

    /**
     * The unbounded read that is not offered. {@code JpaRepository} would have contributed
     * {@code findAll()} and {@code count()} to a table that grows with every registration; the
     * interface extends {@code Repository} instead and lists what it needs, so the method a list
     * screen would reach for does not exist to be reached for.
     */
    @Test
    void theRepository_offersNoUnboundedReadOfATableThatGrowsWithUsers() {
        assertThat(UserAccountRepository.class.getMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("existsByEmailIgnoringCase", "findByEmailIgnoringCase", "findById", "save");

        assertThatThrownBy(() -> UserAccountRepository.class.getMethod("findAll"))
                .isInstanceOf(NoSuchMethodException.class);
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private boolean readOnlyFlagOf(String method, Class<?>... parameterTypes) throws NoSuchMethodException {
        Transactional annotation =
                UserAccountRepository.class.getMethod(method, parameterTypes).getAnnotation(Transactional.class);
        assertThat(annotation)
                .as("%s declares no transaction attribute at all", method)
                .isNotNull();
        return annotation.readOnly();
    }

    private String planFor(String sql) {
        jdbc.sql("set local enable_seqscan = off").update();
        return String.join("\n", jdbc.sql("explain " + sql).query(String.class).list());
    }

    private Statistics statistics() {
        return em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    private int countOfAccountsInThisTransaction() {
        return jdbc.sql("select count(*) from user_account")
                .query(Integer.class)
                .single();
    }

    /** Read on a connection of its own, so it sees only what has been committed. */
    private int committedAccountCount() {
        return onAnotherConnection("select count(*) from user_account", resultSet -> resultSet.getInt(1));
    }

    private String committedRoleOfSeededAdmin() {
        return onAnotherConnection(
                "select role from user_account where user_id = '" + SEEDED_ADMIN + "'",
                resultSet -> resultSet.getString(1));
    }

    private <T> T onAnotherConnection(String sql, RowReader<T> reader) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return reader.read(resultSet);
        } catch (SQLException e) {
            throw new IllegalStateException("could not read on a second connection", e);
        }
    }

    @FunctionalInterface
    private interface RowReader<T> {
        T read(ResultSet resultSet) throws SQLException;
    }
}
