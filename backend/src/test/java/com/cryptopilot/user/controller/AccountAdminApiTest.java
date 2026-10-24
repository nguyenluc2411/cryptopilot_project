package com.cryptopilot.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.support.TestcontainersConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * SCR-34 and SCR-35: an administrator searches accounts, reads one with its activity and history, and locks, unlocks,
 * bans or changes the role of another, each with a reason, audited, ending its sessions; never their own account, and
 * never the last ACTIVE administrator.
 *
 * <p>Every account of a test carries a marker of its own in the email, so accounts other tests leave behind cannot
 * change a search.
 *
 * <p>Rule: SRS 3.11.2, UC-42; BR-05, BR-06, BR-57, BR-58.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AccountAdminApiTest {

    private static final String USERS = "/api/v1/admin/users";

    /** The PRO package of 30 days that V11 seeded. */
    private static final UUID PRO_PACKAGE = UUID.fromString("019b76da-a800-7002-8000-000000000002");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<UUID> accounts = new ArrayList<>();
    private final List<UUID> pairs = new ArrayList<>();
    private String marker;
    private UUID admin;

    @BeforeEach
    void setUp() {
        marker = "t083" + Long.toString(System.nanoTime() % 1_000_000_000L, 36);
        admin = account("ADMIN", "Root Admin");
    }

    @AfterEach
    void removeRows() {
        for (UUID account : accounts) {
            sql.sql("delete from audit_log where user_id = ? or entity_id = ?")
                    .params(account, account)
                    .update();
            sql.sql("delete from user_token where user_id = ?").param(account).update();
            sql.sql("""
                            delete from user_subscription
                             where order_id in (select order_id from subscription_order where user_id = ?)""").param(account).update();
            sql.sql("delete from subscription_order where user_id = ?")
                    .param(account)
                    .update();
            sql.sql("delete from trading_plan where user_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        for (UUID pair : pairs) {
            new MarketTestData(sql, Instant.now()).remove(pair);
        }
    }

    @Test
    void UC42_accounts_areSearchedByText_role_status_andPlan() throws Exception {
        UUID alice = account("TRADER", "Alice Nguyen");
        UUID bob = account("TRADER", "Bob Tran");
        subscribeToPro(bob);
        sql.sql("update user_account set account_status = 'LOCKED' where user_id = ?")
                .param(alice)
                .update();

        as(admin, get(USERS + "?q=" + marker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.pageSize").value(20));
        as(admin, get(USERS + "?q=" + marker + "&role=TRADER"))
                .andExpect(jsonPath("$.total").value(2));
        as(admin, get(USERS + "?q=" + marker + "&status=LOCKED"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].userId").value(alice.toString()));
        as(admin, get(USERS + "?q=" + marker + "&plan=PRO"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].userId").value(bob.toString()))
                .andExpect(jsonPath("$.items[0].plan").value("PRO"));
        as(admin, get(USERS + "?q=" + marker + "&plan=FREE"))
                .andExpect(jsonPath("$.total").value(2));
        as(admin, get(USERS).param("q", "BOB TRAN"))
                .andExpect(jsonPath("$.items[?(@.userId == '" + bob + "')]").isNotEmpty());
        as(alice, get(USERS)).andExpect(status().isForbidden());
    }

    @Test
    void UC42_anAccount_showsItsPlan_activity_andHistory() throws Exception {
        UUID alice = account("TRADER", "Alice");
        subscribeToPro(alice);
        plan(alice);

        as(admin, get(USERS + "/" + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.plan").value("PRO"))
                .andExpect(jsonPath("$.account.displayName").value("Alice"))
                .andExpect(jsonPath("$.activity.plans").value(1))
                .andExpect(jsonPath("$.activity.trades").value(0))
                .andExpect(jsonPath("$.activity.posts").value(0))
                .andExpect(jsonPath("$.recentAudit.length()").value(0));
        as(admin, get(USERS + "/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void BR06_lockingEndsTheSessions_unlockingRestores_andBothAreAuditedWithTheReason() throws Exception {
        UUID alice = account("TRADER", "Alice");
        refreshToken(alice);

        action(alice, "lock", "Suspicious activity")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.status").value("LOCKED"))
                .andExpect(jsonPath("$.recentAudit[0].action").value("ACCOUNT_LOCKED"));
        assertThat(unusedRefreshTokens(alice)).isZero();
        action(alice, "lock", "Again").andExpect(status().isConflict());
        action(alice, "unlock", "Cleared")
                .andExpect(jsonPath("$.account.status").value("ACTIVE"))
                .andExpect(jsonPath("$.recentAudit.length()").value(2));
        assertThat(sql.sql("""
                                select new_value ->> 'reason' from audit_log
                                 where entity_id = ? and action_code = 'ACCOUNT_LOCKED'""").param(alice).query(String.class).single()).isEqualTo("Suspicious activity");
        action(alice, "lock", "").andExpect(status().isBadRequest());
    }

    @Test
    void BR06_aBanIsFinal_andEndsTheSessions() throws Exception {
        UUID alice = account("TRADER", "Alice");
        refreshToken(alice);

        action(alice, "ban", "Scam posts")
                .andExpect(jsonPath("$.account.status").value("BANNED"));
        assertThat(unusedRefreshTokens(alice)).isZero();
        action(alice, "ban", "Again").andExpect(status().isConflict());
        action(alice, "unlock", "Undo").andExpect(status().isConflict());
    }

    @Test
    void BR58_noAdministratorActsOnTheirOwnAccount_orOnTheLastActiveOne() throws Exception {
        action(admin, "lock", "Self")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_STATUS_TRANSITION_INVALID"));
        role(admin, "TRADER").andExpect(status().isConflict());

        UUID other = account("ADMIN", "Second Admin");
        // Lock every other ACTIVE administrator of the database for the duration, so "last" means these two.
        List<UUID> paused = sql.sql("""
                        select user_id from user_account
                         where role = 'ADMIN' and account_status = 'ACTIVE' and user_id not in (?, ?)""").params(admin, other).query(UUID.class).list();
        sql.sql("update user_account set account_status = 'LOCKED' where user_id in (:ids)")
                .param("ids", paused.isEmpty() ? List.of(UUID.randomUUID()) : paused)
                .update();
        try {
            sql.sql("update user_account set account_status = 'LOCKED' where user_id = ?")
                    .param(admin)
                    .update();
            // The caller is not ACTIVE any more in the table, so `other` is the last ACTIVE administrator.
            as(admin, post(USERS + "/" + other + "/ban"), "{\"reason\": \"x\"}").andExpect(status().isConflict());
            as(admin, put(USERS + "/" + other + "/role"), "{\"role\": \"TRADER\", \"reason\": \"x\"}")
                    .andExpect(status().isConflict());
            sql.sql("update user_account set account_status = 'ACTIVE' where user_id = ?")
                    .param(admin)
                    .update();
            role(other, "TRADER")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.account.role").value("TRADER"));
        } finally {
            if (!paused.isEmpty()) {
                sql.sql("update user_account set account_status = 'ACTIVE' where user_id in (:ids)")
                        .param("ids", paused)
                        .update();
            }
        }
    }

    /**
     * BR-58 under concurrency: two administrators, the last two ACTIVE ones, locking each other at the same instant.
     * Each request locks only its target's row, so without the roster lock both counted the other as the one left and
     * both committed; now exactly one succeeds and one administrator stays ACTIVE, round after round.
     */
    @Test
    void BR58_twoAdministratorsLockingEachOtherAtOnce_leaveOneActive() throws Exception {
        UUID other = account("ADMIN", "Second Admin");
        List<UUID> paused = sql.sql("""
                        select user_id from user_account
                         where role = 'ADMIN' and account_status = 'ACTIVE' and user_id not in (?, ?)""").params(admin, other).query(UUID.class).list();
        sql.sql("update user_account set account_status = 'LOCKED' where user_id in (:ids)")
                .param("ids", paused.isEmpty() ? List.of(UUID.randomUUID()) : paused)
                .update();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                sql.sql("update user_account set account_status = 'ACTIVE' where user_id in (?, ?)")
                        .params(admin, other)
                        .update();
                CyclicBarrier start = new CyclicBarrier(2);
                Future<Integer> first = pool.submit(() -> lockAtOnce(admin, other, start));
                Future<Integer> second = pool.submit(() -> lockAtOnce(other, admin, start));

                assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
                assertThat(sql.sql("""
                                select count(*) from user_account
                                 where user_id in (?, ?) and account_status = 'ACTIVE'""").params(admin, other).query(Long.class).single())
                        .isOne();
            }
        } finally {
            pool.shutdownNow();
            if (!paused.isEmpty()) {
                sql.sql("update user_account set account_status = 'ACTIVE' where user_id in (:ids)")
                        .param("ids", paused)
                        .update();
            }
        }
    }

    @Test
    void BR05_aRoleChange_endsTheSessions_andIsAudited() throws Exception {
        UUID alice = account("TRADER", "Alice");
        refreshToken(alice);

        role(alice, "ADMIN")
                .andExpect(jsonPath("$.account.role").value("ADMIN"))
                .andExpect(jsonPath("$.recentAudit[0].action").value("ROLE_CHANGED"));
        assertThat(unusedRefreshTokens(alice)).isZero();
        role(alice, "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentAudit.length()").value(1));
    }

    // ------------------------------------------------------------------------------------------

    private int lockAtOnce(UUID caller, UUID target, CyclicBarrier start) throws Exception {
        start.await();
        return as(caller, post(USERS + "/" + target + "/lock"), "{\"reason\": \"race\"}")
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private ResultActions action(UUID user, String action, String reason) throws Exception {
        return as(admin, post(USERS + "/" + user + "/" + action), "{\"reason\": \"" + reason + "\"}");
    }

    private ResultActions role(UUID user, String role) throws Exception {
        return as(admin, put(USERS + "/" + user + "/role"), "{\"role\": \"" + role + "\", \"reason\": \"test\"}");
    }

    private void refreshToken(UUID user) {
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_token (token_id, user_id, token_type, token_hash, token_family_id, expires_at,
                                                created_at, updated_at)
                        values (?, ?, 'REFRESH', ?, ?, ?, ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        user,
                        UUID.randomUUID().toString().replace("-", ""),
                        UUID.randomUUID(),
                        Timestamp.from(Instant.now().plus(Duration.ofDays(7))),
                        now,
                        now)
                .update();
    }

    private long unusedRefreshTokens(UUID user) {
        return sql.sql(
                        "select count(*) from user_token where user_id = ? and token_type = 'REFRESH' and used_at is null")
                .param(user)
                .query(Long.class)
                .single();
    }

    private void plan(UUID user) {
        UUID pair = new MarketTestData(sql, Instant.now())
                .pair(marker.toUpperCase() + "USDT", true, false, "TRADING", null, 0);
        pairs.add(pair);
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into trading_plan (plan_id, user_id, pair_id, market_type, direction, entry_type,
                                                  stop_loss_price, take_profit_price, capital_amount, risk_percent,
                                                  plan_status, created_at, updated_at)
                        values (?, ?, ?, 'SPOT', 'LONG', 'MARKET', 90, 120, 1000, 1, 'DRAFT', ?, ?)""").params(UUID.randomUUID(), user, pair, now, now).update();
    }

    private void subscribeToPro(UUID user) {
        Instant start = Instant.now().minus(Duration.ofDays(1));
        UUID order = UUID.randomUUID();
        sql.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, created_at, paid_at, expires_at,
                                                        updated_at)
                        values (?, ?, ?, ?, 1, 'VND', 'VNPAY', 'PAID', ?, ?, ?, ?)""")
                .params(
                        order,
                        user,
                        PRO_PACKAGE,
                        "T083-" + order,
                        Timestamp.from(start),
                        Timestamp.from(start),
                        Timestamp.from(start.plusSeconds(900)),
                        Timestamp.from(start))
                .update();
        sql.sql("""
                        insert into user_subscription (subscription_id, order_id, start_at, end_at,
                                                       subscription_status, created_at, updated_at)
                        values (?, ?, ?, ?, 'ACTIVE', ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        order,
                        Timestamp.from(start),
                        Timestamp.from(start.plus(Duration.ofDays(30))),
                        Timestamp.from(start),
                        Timestamp.from(start))
                .update();
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request) throws Exception {
        return as(user, request, null);
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(user));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private UUID account(String role, String displayName) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""")
                .params(id, marker + "-" + id + "@t083.invalid", role, now, now)
                .update();
        sql.sql("insert into user_profile (user_id, display_name, created_at, updated_at) values (?, ?, ?, ?)")
                .params(id, displayName, now, now)
                .update();
        accounts.add(id);
        return id;
    }

    private String bearer(UUID userId) {
        String role = sql.sql("select role from user_account where user_id = ?")
                .param(userId)
                .query(String.class)
                .single();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, role)
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }
}
