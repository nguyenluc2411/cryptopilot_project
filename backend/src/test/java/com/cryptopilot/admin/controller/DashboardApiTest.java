package com.cryptopilot.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.support.TestcontainersConfig;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * SCR-33: the dashboard and the management reports match a fixture dataset. The fixture lives in March 2031, a month
 * no other test writes to, so its figures are exact; and it sits on the edges of the business day, UTC+7 (CR-01): an
 * account created at 23:30 UTC on 28 February counts on 1 March.
 *
 * <p>Rule: UC-40, UC-41; SRS 3.11.1; CR-01.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class DashboardApiTest {

    private static final String ADMIN_API = "/api/v1/admin";
    private static final String MARCH = "from=2031-03-01&to=2031-03-31";
    private static final UUID PRO_MONTHLY = UUID.fromString("019b76da-a800-7002-8000-000000000002");
    private static final UUID PREMIUM_MONTHLY = UUID.fromString("019b76da-a800-7002-8000-000000000004");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<UUID> accounts = new ArrayList<>();
    private UUID admin;

    @BeforeEach
    void fixture() {
        admin = account("ADMIN", "2031-03-02T10:00:00+07:00");
        account("TRADER", "2031-02-28T23:30:00Z");
        UUID alice = account("TRADER", "2031-03-02T10:00:00+07:00");
        account("TRADER", "2031-03-02T23:30:00+07:00");
        UUID bob = account("TRADER", "2031-03-10T09:00:00+07:00");
        account("TRADER", "2031-04-01T00:00:00+07:00");

        order(alice, PRO_MONTHLY, 99_000, "PAID", "2031-03-02T12:00:00+07:00");
        order(bob, PREMIUM_MONTHLY, 199_000, "PAID", "2031-03-10T12:00:00+07:00");
        order(bob, PRO_MONTHLY, 99_000, "PENDING", null);
        order(alice, PRO_MONTHLY, 99_000, "PAID", "2031-04-01T00:00:00+07:00");

        UUID post = post(bob, "2031-03-10T13:00:00+07:00");
        post(bob, "2031-03-10T14:00:00+07:00");
        comment(post, alice, "2031-03-10T15:00:00+07:00");
    }

    @AfterEach
    void removeRows() {
        for (UUID account : accounts) {
            sql.sql("delete from forum_comment where author_id = ?")
                    .param(account)
                    .update();
            sql.sql("delete from forum_post where author_id = ?").param(account).update();
            sql.sql("delete from subscription_order where user_id = ?")
                    .param(account)
                    .update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
    }

    @Test
    void UC41_aDailyReport_matchesTheFixture_dayByDay() throws Exception {
        as(admin, get(ADMIN_API + "/reports?" + MARCH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupBy").value("DAY"))
                .andExpect(jsonPath("$.rows.length()").value(31))
                .andExpect(jsonPath("$.columns[0]").value("newUsers"))
                .andExpect(jsonPath("$.columns[1]").value("paidOrders"))
                .andExpect(jsonPath("$.columns[2]").value("revenue"))
                .andExpect(jsonPath("$.columns[3]").value("revenue:PREMIUM_MONTHLY"))
                .andExpect(jsonPath("$.columns[4]").value("revenue:PRO_MONTHLY"))
                .andExpect(jsonPath("$.rows[0].period").value("2031-03-01"))
                .andExpect(jsonPath("$.rows[0].coveredFrom").value("2031-03-01"))
                .andExpect(jsonPath("$.rows[0].coveredTo").value("2031-03-01"))
                .andExpect(jsonPath("$.rows[0].values.newUsers").value(1))
                .andExpect(jsonPath("$.rows[1].values.newUsers").value(2))
                .andExpect(jsonPath("$.rows[1].values.paidOrders").value(1))
                .andExpect(jsonPath("$.rows[1].values.revenue").value(99000))
                .andExpect(jsonPath("$.rows[1].values['revenue:PRO_MONTHLY']").value(99000))
                .andExpect(jsonPath("$.rows[9].period").value("2031-03-10"))
                .andExpect(jsonPath("$.rows[9].values.newUsers").value(1))
                .andExpect(jsonPath("$.rows[9].values.revenue").value(199000))
                .andExpect(
                        jsonPath("$.rows[9].values['revenue:PREMIUM_MONTHLY']").value(199000))
                .andExpect(jsonPath("$.rows[9].values.newPosts").value(2))
                .andExpect(jsonPath("$.rows[9].values.newComments").value(1))
                .andExpect(jsonPath("$.rows[30].values.newUsers").value(0));
    }

    @Test
    void UC41_weeksStartOnMonday_andAMonthAddsUp() throws Exception {
        as(admin, get(ADMIN_API + "/reports?" + MARCH + "&groupBy=WEEK"))
                .andExpect(jsonPath("$.rows.length()").value(6))
                .andExpect(jsonPath("$.rows[0].period").value("2031-02-24"))
                .andExpect(jsonPath("$.rows[0].values.newUsers").value(3))
                .andExpect(jsonPath("$.rows[2].period").value("2031-03-10"))
                .andExpect(jsonPath("$.rows[2].values.revenue").value(199000));
        as(admin, get(ADMIN_API + "/reports?" + MARCH + "&groupBy=MONTH"))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].values.newUsers").value(4))
                .andExpect(jsonPath("$.rows[0].values.paidOrders").value(2))
                .andExpect(jsonPath("$.rows[0].values.revenue").value(298000));
    }

    @Test
    void UC41_theExport_isTheSameReportAsCsv() throws Exception {
        String csv = as(admin, get(ADMIN_API + "/reports/export?" + MARCH + "&groupBy=MONTH"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8"))
                .andExpect(header().string(
                                HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"cryptopilot-report-2031-03-01-2031-03-31-month.csv\""))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(csv.split("\r\n"))
                .containsExactly(
                        "period,coveredFrom,coveredTo,newUsers,paidOrders,revenue,revenue:PREMIUM_MONTHLY,"
                                + "revenue:PRO_MONTHLY,newPosts,newComments,aiQuestions",
                        "2031-03-01,2031-03-01,2031-03-31,4,2,298000,199000,99000,2,1,0");
    }

    @Test
    void UC40_theDashboard_countsThePeriod() throws Exception {
        as(admin, get(ADMIN_API + "/dashboard?" + MARCH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2031-03-01"))
                .andExpect(jsonPath("$.indicators.newUsers").value(4))
                .andExpect(jsonPath("$.indicators.paidOrders").value(2))
                .andExpect(jsonPath("$.indicators.revenue").value(298000))
                .andExpect(jsonPath("$.indicators.revenueByPackage.PRO_MONTHLY").value(99000))
                .andExpect(jsonPath("$.indicators.newPosts").value(2))
                .andExpect(jsonPath("$.indicators.newComments").value(1))
                .andExpect(jsonPath("$.indicators.aiQuestions").value(0))
                .andExpect(jsonPath("$.indicators.totalUsers").exists())
                .andExpect(jsonPath("$.indicators.activeUsers").exists())
                .andExpect(jsonPath("$.indicators.activeSubscriptions").exists())
                .andExpect(jsonPath("$.indicators.openReports").exists())
                .andExpect(jsonPath("$.indicators.latestCandle").exists());
        as(admin, get(ADMIN_API + "/dashboard")).andExpect(status().isOk());
    }

    @Test
    void UC41_aPeriodBackwards_orTooManyRows_isRefused_andTradersHaveNoAccess() throws Exception {
        as(admin, get(ADMIN_API + "/reports?from=2031-03-02&to=2031-03-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.from").value("MSG15"));
        as(admin, get(ADMIN_API + "/reports?from=2030-01-01&to=2031-03-01")).andExpect(status().isBadRequest());
        as(admin, get(ADMIN_API + "/reports?from=2030-01-01&to=2031-03-01&groupBy=MONTH"))
                .andExpect(status().isOk());
        UUID trader = account("TRADER", "2031-03-05T10:00:00+07:00");
        as(trader, get(ADMIN_API + "/dashboard")).andExpect(status().isForbidden());
        as(trader, get(ADMIN_API + "/reports/export")).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------

    private UUID account(String role, String createdAt) {
        UUID id = UUID.randomUUID();
        Timestamp at = at(createdAt);
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@t086.invalid", role, at, at).update();
        accounts.add(id);
        return id;
    }

    private void order(UUID user, UUID packageId, long amount, String status, String paidAt) {
        UUID id = UUID.randomUUID();
        Timestamp created = paidAt == null ? at("2031-03-05T10:00:00+07:00") : at(paidAt);
        sql.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, created_at, paid_at, expires_at,
                                                        updated_at)
                        values (?, ?, ?, ?, ?, 'VND', 'VNPAY', ?, ?, ?, ?, ?)""")
                .params(
                        id,
                        user,
                        packageId,
                        "T086-" + id,
                        amount,
                        status,
                        created,
                        paidAt == null ? null : at(paidAt),
                        Timestamp.from(created.toInstant().plusSeconds(900)),
                        created)
                .update();
    }

    private UUID post(UUID author, String createdAt) {
        UUID id = UUID.randomUUID();
        Timestamp at = at(createdAt);
        sql.sql("""
                        insert into forum_post (post_id, author_id, post_type, title, content, post_status, created_at,
                                                updated_at)
                        values (?, ?, 'TEXT', 'Fixture post', 'text', 'PUBLISHED', ?, ?)""").params(id, author, at, at).update();
        return id;
    }

    private void comment(UUID post, UUID author, String createdAt) {
        Timestamp at = at(createdAt);
        sql.sql("""
                        insert into forum_comment (comment_id, post_id, author_id, content, comment_status, created_at,
                                                   updated_at)
                        values (?, ?, ?, 'Nice', 'PUBLISHED', ?, ?)""").params(UUID.randomUUID(), post, author, at, at).update();
    }

    private static Timestamp at(String instant) {
        return Timestamp.from(OffsetDateTime.parse(instant).toInstant());
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(user));
        return mvc.perform(request);
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
