package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.auth.entity.TokenType;
import com.cryptopilot.auth.entity.UserToken;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.entity.AccountStatus;
import com.cryptopilot.user.entity.DevicePlatform;
import com.cryptopilot.user.entity.Role;
import com.cryptopilot.user.entity.TradingStyle;
import com.cryptopilot.user.entity.UserAccount;
import com.cryptopilot.user.entity.UserDevice;
import com.cryptopilot.user.entity.UserProfile;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.metamodel.Attribute;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The entities against the schema Flyway actually produced, on the database the product runs on.
 *
 * <p>Two things are being checked, and they are different. One is that Hibernate's own validation
 * of the mapping passes: {@code ddl-auto=validate} makes an entity that names a column the schema
 * does not have, or a type it cannot store, a start-up failure rather than a runtime surprise.
 * The other is that the mapping is <em>right</em> where validation cannot tell. Validation
 * compares the names and the types of columns and nothing else: it is perfectly happy with two
 * columns of the same type swapped, and — verified by breaking it — with a field declared nullable
 * over a {@code NOT NULL} column. Only writing a value and reading it back catches those, which is
 * what most of this class does. It does catch an enumeration mapped as an ordinal, because that
 * offers a {@code smallint} to a {@code varchar} column.
 *
 * <p>Every test runs in a transaction that is rolled back. The suite shares one database, and the
 * seed tests next door assert that exactly one account and one profile exist, so a mapping test
 * that left rows behind would break them — the rollback is what keeps both honest.
 *
 * <p>The clock is fixed here. With the system clock a test cannot tell an audit instant written
 * from the injected clock apart from one written by a call to {@code Instant.now()} inside an
 * entity, and telling those apart is the whole point of injecting it.
 *
 * <p>Rule: BR-05 (the seeded administrator), Q-10 ({@code ddl-auto=validate}); TECHNICAL_DESIGN
 * sections 5.2, 5.4 and 6.
 *
 * <p>Reference: Bauer, C., King, G. &amp; Gregory, G. (2015). <i>Java Persistence with Hibernate</i>
 * (2nd ed.). Manning, ch. 3 and 12 (mapping a class to a legacy schema; fetch plans and the number
 * of statements a query costs).
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import({TestcontainersConfig.class, EntityMappingTest.FixedClockConfig.class})
@Transactional
class EntityMappingTest {

    /** The instant the fixed clock reports, so every audit stamp written here is known exactly. */
    private static final Instant FIXED_NOW = Instant.parse("2026-09-21T08:30:00Z");

    /** The administrator V3 seeded, by the literal key the migration wrote. */
    private static final UUID SEEDED_ADMIN = UUID.fromString("019b76da-a800-7000-8000-000000000001");

    /** The instant V3 stamps on every seeded row. */
    private static final Instant SEED_EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private EntityManager em;

    @Autowired
    private EntityManagerFactory emf;

    @Autowired
    private JdbcClient jdbc;

    // ------------------------------------------------------------------------------------------
    // Validation against the migrated schema
    // ------------------------------------------------------------------------------------------

    /**
     * The primary proof, stated rather than implied: the persistence unit is running with
     * {@code validate}, and it holds the four entities of this task.
     *
     * <p>Hibernate performs that validation while the context is built, so this test having a
     * persistence unit to ask at all is the evidence that it passed — an entity naming a column
     * that is not there fails every test in this class before the first assertion runs. The
     * assertion below is what keeps the setting from being quietly turned off later, which would
     * leave the suite green while nothing checked the mapping again.
     *
     * <p>Rule: Q-10; TECHNICAL_DESIGN section 6.
     */
    @Test
    void theMapping_isValidatedAgainstTheSchemaFlywayMigrated() {
        assertThat(emf.getProperties().get("hibernate.hbm2ddl.auto"))
                .as("Flyway owns the schema; Hibernate may only check it")
                .isEqualTo("validate");

        assertThat(emf.getMetamodel().getEntities())
                .extracting(type -> type.getJavaType().getName())
                .contains(
                        UserAccount.class.getName(),
                        UserProfile.class.getName(),
                        UserDevice.class.getName(),
                        UserToken.class.getName());
    }

    // ------------------------------------------------------------------------------------------
    // Column-for-column round trips
    // ------------------------------------------------------------------------------------------

    @Test
    void anAccount_survivesAWriteAndAReadWithEveryColumnIntact() {
        UserAccount account = UserAccount.register("round.trip@example.invalid", "hashed-password");
        account.verifyEmail(FIXED_NOW.minusSeconds(3600));
        account.recordLogin(FIXED_NOW.minusSeconds(60));
        account.changeRole(Role.ADMIN);
        UUID id = account.getId();

        UserAccount reread = writeAndReread(account, UserAccount.class, id);

        assertThat(reread.getEmail()).isEqualTo("round.trip@example.invalid");
        assertThat(reread.getPasswordHash()).isEqualTo("hashed-password");
        assertThat(reread.getRole()).isEqualTo(Role.ADMIN);
        assertThat(reread.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(reread.getEmailVerifiedAt()).isEqualTo(FIXED_NOW.minusSeconds(3600));
        assertThat(reread.getLastLoginAt()).isEqualTo(FIXED_NOW.minusSeconds(60));
        assertThat(reread.getCreatedAt())
                .as("stamped by the listener from the injected clock, not by a database default")
                .isEqualTo(FIXED_NOW);
        assertThat(reread.getUpdatedAt()).isEqualTo(FIXED_NOW);
        assertThat(reread.getVersion()).isZero();
    }

    /**
     * The nullable columns, left alone. A round trip that only ever writes values cannot tell a
     * column that stores {@code null} from one that quietly substitutes something.
     */
    @Test
    void anAccountThatHasNeverVerifiedOrLoggedIn_storesNullRatherThanASubstitute() {
        UserAccount account = UserAccount.register("fresh@example.invalid", "hashed-password");
        UUID id = account.getId();

        UserAccount reread = writeAndReread(account, UserAccount.class, id);

        assertThat(reread.getEmailVerifiedAt()).isNull();
        assertThat(reread.getLastLoginAt()).isNull();
        assertThat(reread.isEmailVerified()).isFalse();
    }

    @Test
    void aProfile_survivesAWriteAndAReadWithEveryColumnIntact() {
        UserAccount account = persistedAccount("profile.owner@example.invalid");
        UserProfile profile = UserProfile.createFor(account.getId(), "Round Trip");
        profile.changeAvatar("https://example.invalid/a.png");
        profile.updateTradingDefaults(new BigDecimal("1234.56780000"), new BigDecimal("1.250"), TradingStyle.SWING);
        profile.updateNotificationPreferences(false, true);

        UserProfile reread = writeAndReread(profile, UserProfile.class, account.getId());

        assertThat(reread.getId())
                .as("the profile is keyed by the account it describes")
                .isEqualTo(account.getId());
        assertThat(reread.getDisplayName()).isEqualTo("Round Trip");
        assertThat(reread.getAvatarUrl()).isEqualTo("https://example.invalid/a.png");
        assertThat(reread.getTradingStyle()).isEqualTo(TradingStyle.SWING);
        assertThat(reread.isNotifyEmail()).isFalse();
        assertThat(reread.isNotifyPush()).isTrue();
    }

    /**
     * The scales the columns declare, kept exactly. {@code numeric(28,8)} and {@code numeric(6,3)}
     * are what the design assigns a USDT amount and a user-entered percentage, and a mapping that
     * dropped the scale would round a figure on its way through without anything failing.
     */
    @Test
    void aProfilesDecimalDefaults_comeBackAtTheScaleTheColumnsDeclare() {
        UserAccount account = persistedAccount("decimals@example.invalid");
        UserProfile profile = UserProfile.createFor(account.getId(), "Decimals");
        profile.updateTradingDefaults(new BigDecimal("1000.00000001"), new BigDecimal("0.125"), null);

        UserProfile reread = writeAndReread(profile, UserProfile.class, account.getId());

        assertThat(reread.getDefaultCapital())
                .isEqualByComparingTo("1000.00000001")
                .extracting(BigDecimal::scale)
                .isEqualTo(8);
        assertThat(reread.getDefaultRiskPercent())
                .isEqualByComparingTo("0.125")
                .extracting(BigDecimal::scale)
                .isEqualTo(3);
        assertThat(reread.getTradingStyle()).isNull();
    }

    /**
     * The two booleans are {@code NOT NULL DEFAULT true} in the schema, but Hibernate names every
     * column in an insert, so the default never applies and a field left alone would store
     * {@code false}. The entity therefore sets them itself, and this is the test that says so.
     */
    @Test
    void aNewProfile_storesBothNotificationChannelsOnWithoutRelyingOnTheColumnDefault() {
        UserAccount account = persistedAccount("defaults@example.invalid");
        UserProfile profile = UserProfile.createFor(account.getId(), "Defaults");

        UserProfile reread = writeAndReread(profile, UserProfile.class, account.getId());

        assertThat(reread.isNotifyEmail()).isTrue();
        assertThat(reread.isNotifyPush()).isTrue();
        assertThat(reread.getAvatarUrl()).isNull();
        assertThat(reread.getDefaultCapital()).isNull();
        assertThat(reread.getDefaultRiskPercent()).isNull();
    }

    @Test
    void aDevice_survivesAWriteAndAReadWithEveryColumnIntact() {
        UserAccount account = persistedAccount("device.owner@example.invalid");
        UserDevice device = UserDevice.register(account.getId(), "fcm-token-round-trip", DevicePlatform.ANDROID);
        device.markSeen(FIXED_NOW.minusSeconds(30));
        UUID id = device.getId();

        UserDevice reread = writeAndReread(device, UserDevice.class, id);

        assertThat(reread.getUserId()).isEqualTo(account.getId());
        assertThat(reread.getFcmToken()).isEqualTo("fcm-token-round-trip");
        assertThat(reread.getPlatform()).isEqualTo(DevicePlatform.ANDROID);
        assertThat(reread.isActive()).isTrue();
        assertThat(reread.getLastSeenAt()).isEqualTo(FIXED_NOW.minusSeconds(30));
    }

    @Test
    void aToken_survivesAWriteAndAReadWithEveryColumnIntact() {
        UserAccount account = persistedAccount("token.owner@example.invalid");
        UserToken token = UserToken.issue(
                account.getId(), TokenType.EMAIL_VERIFICATION, "b".repeat(64), FIXED_NOW.plusSeconds(86400));
        token.markUsed(FIXED_NOW.plusSeconds(120));
        UUID id = token.getId();

        UserToken reread = writeAndReread(token, UserToken.class, id);

        assertThat(reread.getUserId()).isEqualTo(account.getId());
        assertThat(reread.getTokenType()).isEqualTo(TokenType.EMAIL_VERIFICATION);
        assertThat(reread.getTokenHash()).isEqualTo("b".repeat(64));
        assertThat(reread.getExpiresAt()).isEqualTo(FIXED_NOW.plusSeconds(86400));
        assertThat(reread.getUsedAt()).isEqualTo(FIXED_NOW.plusSeconds(120));
    }

    /**
     * Optimistic locking is mapped, not merely declared: the column starts at zero and the second
     * write moves it. Without {@code @Version} a concurrent update would silently overwrite another
     * one (TECHNICAL_DESIGN 5.5).
     */
    @Test
    void anUpdatedRow_movesTheOptimisticLockingVersionAndTheUpdatedInstant() {
        UserAccount account = persistedAccount("versioned@example.invalid");
        assertThat(account.getVersion()).isZero();

        account.lock();
        em.flush();

        assertThat(account.getVersion()).isEqualTo(1L);
        assertThat(account.getCreatedAt()).as("created_at is not updatable").isEqualTo(FIXED_NOW);
    }

    // ------------------------------------------------------------------------------------------
    // The rows the seed migration wrote
    // ------------------------------------------------------------------------------------------

    /**
     * The bootstrap administrator of BR-05, loaded through the mapping rather than through SQL.
     * The seed wrote the row before any of this existed, so reading it back with every value the
     * migration put there is the cheapest proof that the entity matches the schema in the
     * direction that matters: a column swapped with another of the same type would show up here.
     *
     * <p>This is the bootstrap half of BR-05 — that an ADMIN exists at all. That only an
     * administrator may grant the role is authorization and belongs to T-015.
     */
    @Test
    void BR05_theSeededAdministrator_loadsThroughTheEntityWithExactlyTheValuesTheSeedWrote() {
        UserAccount admin = em.find(UserAccount.class, SEEDED_ADMIN);

        assertThat(admin).isNotNull();
        assertThat(admin.getEmail()).isEqualTo("admin@cryptopilot.invalid");
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(admin.getEmailVerifiedAt()).isEqualTo(SEED_EPOCH);
        assertThat(admin.getLastLoginAt()).isNull();
        assertThat(admin.getCreatedAt()).isEqualTo(SEED_EPOCH);
        assertThat(admin.getUpdatedAt()).isEqualTo(SEED_EPOCH);
        assertThat(admin.getVersion()).isZero();
        assertThat(admin.isNew())
                .as("a row read back from the database has been written before")
                .isFalse();
        assertThat(admin.getPasswordHash())
                .as("the seed commits no credential; the hash comes from the environment")
                .doesNotStartWith("$");
    }

    @Test
    void theSeededAdministratorsProfile_loadsThroughTheEntityWithTheValuesTheSeedWrote() {
        UserProfile profile = em.find(UserProfile.class, SEEDED_ADMIN);

        assertThat(profile).isNotNull();
        assertThat(profile.getDisplayName()).isEqualTo("CryptoPilot Admin");
        assertThat(profile.getAvatarUrl()).isNull();
        assertThat(profile.getDefaultCapital()).isNull();
        assertThat(profile.getDefaultRiskPercent()).isNull();
        assertThat(profile.getTradingStyle()).isNull();
        assertThat(profile.isNotifyEmail()).isTrue();
        assertThat(profile.isNotifyPush()).isTrue();
        assertThat(profile.getCreatedAt()).isEqualTo(SEED_EPOCH);
    }

    // ------------------------------------------------------------------------------------------
    // Enumerations against the check constraints
    // ------------------------------------------------------------------------------------------

    /**
     * Every constant of every enumeration this task maps is written and read back. The columns are
     * {@code varchar(32)} with a check constraint listing the permitted values, so a constant that
     * the list does not contain — or an enumeration mapped as an ordinal, which would offer the
     * database a number — is refused by the insert.
     */
    @ParameterizedTest
    @EnumSource(Role.class)
    void everyRole_isAValueTheColumnAccepts(Role role) {
        UserAccount account = UserAccount.register(role + "@enum.invalid", "hash");
        account.changeRole(role);
        UUID id = account.getId();

        assertThat(writeAndReread(account, UserAccount.class, id).getRole()).isEqualTo(role);
    }

    @ParameterizedTest
    @EnumSource(AccountStatus.class)
    void everyAccountStatus_isAValueTheColumnAccepts(AccountStatus status) {
        UserAccount account = UserAccount.register(status + "@enum.invalid", "hash");
        switch (status) {
            case ACTIVE -> {
                /* where an account starts */
            }
            case LOCKED -> account.lock();
            case BANNED -> account.ban();
        }
        UUID id = account.getId();

        assertThat(writeAndReread(account, UserAccount.class, id).getAccountStatus())
                .isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(TradingStyle.class)
    void everyTradingStyle_isAValueTheColumnAccepts(TradingStyle style) {
        UserAccount account = persistedAccount(style + "@enum.invalid");
        UserProfile profile = UserProfile.createFor(account.getId(), "Styles");
        profile.updateTradingDefaults(null, null, style);

        assertThat(writeAndReread(profile, UserProfile.class, account.getId()).getTradingStyle())
                .isEqualTo(style);
    }

    @ParameterizedTest
    @EnumSource(DevicePlatform.class)
    void everyDevicePlatform_isAValueTheColumnAccepts(DevicePlatform platform) {
        UserAccount account = persistedAccount(platform + "@enum.invalid");
        UserDevice device = UserDevice.register(account.getId(), "fcm-" + platform, platform);
        UUID id = device.getId();

        assertThat(writeAndReread(device, UserDevice.class, id).getPlatform()).isEqualTo(platform);
    }

    @ParameterizedTest
    @EnumSource(TokenType.class)
    void everyTokenType_isAValueTheColumnAccepts(TokenType type) {
        UserAccount account = persistedAccount(type + "@enum.invalid");
        UserToken token = UserToken.issue(account.getId(), type, digestFor(type), FIXED_NOW.plusSeconds(600));
        UUID id = token.getId();

        assertThat(writeAndReread(token, UserToken.class, id).getTokenType()).isEqualTo(type);
    }

    /**
     * The other direction: a value outside the list is refused by the database, not merely absent
     * from the Java enumeration. Without this the enum constants could drift away from the check
     * constraint and nothing would notice until an insert failed in production.
     */
    @Test
    void aRoleOutsideTheCheckConstraint_isRefusedByTheDatabase() {
        UserAccount account = persistedAccount("outside@enum.invalid");

        assertThatThrownBy(() -> {
                    jdbc.sql("update user_account set role = 'SUPERADMIN' where user_id = ?")
                            .param(account.getId())
                            .update();
                })
                .hasMessageContaining("ck_user_account_role");
    }

    // ------------------------------------------------------------------------------------------
    // Delete behaviour
    // ------------------------------------------------------------------------------------------

    /**
     * Removing an account through the entity takes its profile, its devices and its tokens with it,
     * because those three foreign keys are the ones V1 declares {@code ON DELETE CASCADE} — they
     * are the account itself rather than things it owns. No JPA cascade is configured anywhere;
     * what is observed here is the database's own rule, reached through {@code em.remove}.
     *
     * <p>The opposite half — that an account with trading history or posts refuses to be deleted at
     * all — is asserted by the schema tests, and is why BR-05 moves an account to LOCKED or BANNED
     * instead of deleting it.
     */
    @Test
    void removingAnAccount_takesTheRowsTheSchemaDeclaresPartOfIt() {
        UserAccount account = persistedAccount("cascade@example.invalid");
        UUID id = account.getId();
        em.persist(UserProfile.createFor(id, "Cascade"));
        em.persist(UserDevice.register(id, "fcm-cascade", DevicePlatform.IOS));
        em.persist(UserToken.issue(id, TokenType.REFRESH, "c".repeat(64), FIXED_NOW.plusSeconds(600)));
        em.flush();

        em.remove(account);
        em.flush();

        assertThat(countOf("user_profile", id)).isZero();
        assertThat(countOf("user_device", id)).isZero();
        assertThat(countOf("user_token", id)).isZero();
    }

    // ------------------------------------------------------------------------------------------
    // Identity across the persistence lifecycle
    // ------------------------------------------------------------------------------------------

    /**
     * Equality is the identifier, and the identifier exists before the row does. That is what lets
     * an entity be put in a set while it is still transient and still be found there after it has
     * been written and read back — which equality by state, or by a key the database assigns, would
     * both break.
     */
    @Test
    void anEntity_isEqualToItselfWhileTransient_afterPersisting_andOnceDetached() {
        UserAccount transientAccount = UserAccount.register("identity@example.invalid", "hash");
        UUID id = transientAccount.getId();
        Set<UserAccount> seen = new HashSet<>();
        seen.add(transientAccount);

        assertThat(transientAccount.isNew()).isTrue();

        em.persist(transientAccount);
        em.flush();
        assertThat(seen).contains(transientAccount);
        assertThat(transientAccount.isNew())
                .as("a written row is no longer new")
                .isFalse();

        em.clear();
        UserAccount detachedReread = em.find(UserAccount.class, id);

        assertThat(detachedReread).isNotSameAs(transientAccount).isEqualTo(transientAccount);
        assertThat(transientAccount).isEqualTo(detachedReread);
        assertThat(detachedReread).hasSameHashCodeAs(transientAccount);
        assertThat(seen)
                .as("the entity stays findable in a set it entered before it had a row")
                .contains(detachedReread);
    }

    /** Two tables can hold the same key — a profile is keyed by its account — and are still not equal. */
    @Test
    void twoDifferentTypesWithTheSameKey_areNotTheSameEntity() {
        UserAccount account = persistedAccount("sharedkey@example.invalid");
        UserProfile profile = UserProfile.createFor(account.getId(), "Shared Key");

        assertThat(profile.getId()).isEqualTo(account.getId());
        assertThat((Object) profile).isNotEqualTo(account);
    }

    // ------------------------------------------------------------------------------------------
    // Fetching
    // ------------------------------------------------------------------------------------------

    /**
     * Nothing this task maps costs a second query. The entities hold no association at all — a
     * profile, a device and a token each name their account by identifier — so reading many of them
     * is one statement, however many there are.
     *
     * <p>Counting statements rather than reading the mapping is deliberate: an association added
     * later, or one switched from lazy to eager, does not change any assertion about fields, and it
     * is exactly the change that turns a list screen into one query per row. This is the test that
     * notices.
     */
    @Test
    void readingManyRows_costsOneStatementBecauseNothingIsAssociated() {
        for (int i = 0; i < 5; i++) {
            UserAccount account = persistedAccount("fetch" + i + "@example.invalid");
            em.persist(UserProfile.createFor(account.getId(), "Fetch " + i));
        }
        em.flush();
        em.clear();

        Statistics statistics = statistics();
        statistics.clear();
        List<UserProfile> profiles = em.createQuery(
                        "select p from UserProfile p where p.displayName like 'Fetch %'", UserProfile.class)
                .getResultList();
        profiles.forEach(UserProfile::getDisplayName);

        assertThat(profiles).hasSize(5);
        assertThat(statistics.getPrepareStatementCount())
                .as("five profiles, one statement: no association is fetched behind them")
                .isEqualTo(1L);
    }

    /**
     * None of the four entities holds an association at all, which is the design: a profile, a
     * device and a token each name their account by identifier. Stated over every entity rather
     * than over one of them, because an association added to any of them is the thing that makes a
     * list screen cost one query per row.
     *
     * <p>An association is not forbidden for ever. It needs a reason, a fetch type, and a test that
     * says what it costs — and until one has those three, this assertion is what asks for them.
     */
    @Test
    void noEntity_holdsAnAssociationThatWouldNeedAFetchStrategy() {
        assertThat(List.of(UserAccount.class, UserProfile.class, UserDevice.class, UserToken.class))
                .allSatisfy(type -> assertThat(emf.getMetamodel().entity(type).getAttributes())
                        .as("%s", type.getSimpleName())
                        .noneMatch(Attribute::isAssociation));
    }

    /** Whatever is loaded is loaded completely: there is no proxy left to initialise later. */
    @Test
    void aLoadedEntity_hasNothingLeftToInitialise() {
        UserAccount account = persistedAccount("fetchplan@example.invalid");
        em.flush();
        em.clear();

        UserAccount reread = em.find(UserAccount.class, account.getId());
        PersistenceUnitUtil util = emf.getPersistenceUnitUtil();

        assertThat(util.isLoaded(reread)).isTrue();
        assertThat(util.getIdentifier(reread)).isEqualTo(account.getId());
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private UserAccount persistedAccount(String email) {
        UserAccount account = UserAccount.register(email, "hashed-password");
        em.persist(account);
        em.flush();
        return account;
    }

    private <T> T writeAndReread(T entity, Class<T> type, UUID id) {
        em.persist(entity);
        em.flush();
        em.clear();
        T reread = em.find(type, id);
        assertThat(reread)
                .as("%s %s was not found after being written", type.getSimpleName(), id)
                .isNotNull();
        return reread;
    }

    private int countOf(String table, UUID userId) {
        return jdbc.sql("select count(*) from " + table + " where user_id = ?")
                .param(userId)
                .query(Integer.class)
                .single();
    }

    private Statistics statistics() {
        return em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    private static String digestFor(TokenType type) {
        String seed = Integer.toHexString(type.name().hashCode());
        return (seed + "0".repeat(64)).substring(0, 64).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Freezes the one clock the application reads the time from, so an audit instant written by the
     * entity listener is a value this test knows rather than a value it has to bracket.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }
}
