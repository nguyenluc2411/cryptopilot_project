package com.cryptopilot.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.billing.client.GatewayUnavailableException;
import com.cryptopilot.billing.client.SePayOrderApi;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payment page of the administration console end to end against PostgreSQL (SCR-40, UC-48): the filtered list,
 * one order, asking SePay about an order (its query API is a mock), and recording how a flagged order was handled.
 * Each test rolls back, so the paid order's receipt is never sent.
 *
 * <p>Rule: UC-48; NSF-13; BR-53, BR-54, BR-57; D-146.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@TestPropertySource(
        properties = {
            "cryptopilot.billing.sepay.merchant-id=MERCHANT_TEST",
            "cryptopilot.billing.sepay.secret-key=spsk_test_secret",
            "cryptopilot.billing.sepay.ipn-secret-key=ipn-secret"
        })
@Transactional
class AdminOrderApiTest {

    private static final String ORDERS = "/api/v1/admin/orders";

    private static final UUID PRO_MONTHLY = UUID.fromString("019b76da-a800-7002-8000-000000000002");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private SePayOrderApi sepay;

    private UUID admin;
    private UUID trader;

    @BeforeEach
    void setUp() {
        admin = account(UserRole.ADMIN);
        trader = account(UserRole.TRADER);
    }

    @Test
    void UC48_thePaymentPage_isForAdministratorsOnly() throws Exception {
        as(trader, get(ORDERS)).andExpect(status().isForbidden());
        as(trader, post(ORDERS + "/CP1/reconcile")).andExpect(status().isForbidden());
    }

    @Test
    void UC48_theList_filtersByEveryCriterion_newestFirst() throws Exception {
        Instant now = Instant.now();
        String old = order(trader, "EXPIRED", now.minus(Duration.ofDays(3)), false);
        String pending = order(trader, "PENDING", now.minus(Duration.ofMinutes(1)), false);
        String flagged = order(trader, "PAID", now, true);
        UUID other = account(UserRole.TRADER);
        String others = order(other, "PAID", now, false);

        as(admin, get(ORDERS).param("userId", trader.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].orderCode").value(flagged))
                .andExpect(jsonPath("$.items[0].packageCode").value("PRO_MONTHLY"))
                .andExpect(jsonPath("$.items[0].tier").value("PRO"))
                .andExpect(jsonPath("$.items[0].manualHandling").value(true))
                .andExpect(jsonPath("$.items[2].orderCode").value(old));
        as(admin, get(ORDERS).param("userId", trader.toString()).param("status", "PENDING"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].orderCode").value(pending));
        as(
                        admin,
                        get(ORDERS)
                                .param("manual", "true")
                                .param("orderCode", flagged.substring(0, 12).toLowerCase()))
                .andExpect(
                        jsonPath("$.items[?(@.orderCode == '" + flagged + "')]").exists())
                .andExpect(jsonPath("$.items[?(@.manualHandling == false)]").isEmpty());
        as(admin, get(ORDERS).param("orderCode", pending))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].gateway").value("SEPAY"))
                .andExpect(jsonPath("$.items[0].orderType").value("NEW"));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        as(
                        admin,
                        get(ORDERS)
                                .param("userId", trader.toString())
                                .param("gateway", "SEPAY")
                                .param("orderType", "NEW")
                                .param("from", today.minusDays(1).toString())
                                .param("to", today.toString()))
                .andExpect(jsonPath("$.total").value(2));
        as(admin, get(ORDERS).param("userId", other.toString()).param("manual", "false"))
                .andExpect(jsonPath("$.items[0].orderCode").value(others));
    }

    @Test
    void UC48_theList_refusesAnUnknownValue_andABackwardsPeriod() throws Exception {
        as(admin, get(ORDERS).param("status", "LOST")).andExpect(status().isBadRequest());
        as(admin, get(ORDERS).param("from", "2026-11-08").param("to", "2026-11-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.from").value("MSG15"));
    }

    @Test
    void UC48_oneOrder_showsWhatTheGatewayAnswered_orIsNotFound() throws Exception {
        String flagged = order(trader, "PAID", Instant.now(), true);
        sql.sql("""
                        update subscription_order set gateway_transaction_no = 'TX-1', gateway_response_code = 'CAPTURED',
                               gateway_payload = '{"order": {"order_status": "CAPTURED"}}'::jsonb
                         where order_code = ?""").param(flagged).update();

        as(admin, get(ORDERS + "/" + flagged))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.orderCode").value(flagged))
                .andExpect(jsonPath("$.order.userId").value(trader.toString()))
                .andExpect(jsonPath("$.gatewayTransactionNo").value("TX-1"))
                .andExpect(jsonPath("$.gatewayResponseCode").value("CAPTURED"))
                .andExpect(jsonPath("$.gatewayPayload").value(org.hamcrest.Matchers.containsString("CAPTURED")))
                .andExpect(jsonPath("$.subscription").doesNotExist());
        as(admin, get(ORDERS + "/CP-NO-SUCH-ORDER")).andExpect(status().isNotFound());
    }

    @Test
    void NSF13_reconciling_settlesAPaymentTheIpnMissed_andIsAudited() throws Exception {
        String code = order(trader, "PENDING", Instant.now().minus(Duration.ofMinutes(1)), false);
        when(sepay.orderDetail(code)).thenReturn(Optional.of("""
                {"data": {"order_invoice_number": "%s", "order_status": "CAPTURED", "order_amount": "99000.00",
                          "transactions": [{"id": "tx-reconciled"}]}}""".formatted(code)));

        as(admin, post(ORDERS + "/" + code + "/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("PAID"))
                .andExpect(jsonPath("$.gatewayTransactionNo").value("tx-reconciled"))
                .andExpect(jsonPath("$.subscription.status").value("ACTIVE"));

        assertThat(audits("ORDER_RECONCILED", code)).isEqualTo(1);
        entityManager.flush();
        assertThat(sql.sql(
                                "select new_value::text from audit_log where user_id = ? and action_code = 'ORDER_RECONCILED'")
                        .param(admin)
                        .query(String.class)
                        .single())
                .contains("PAID");
    }

    @Test
    void BR54_reconcilingAnUnpaidOrderPastItsWindow_expiresIt() throws Exception {
        String code = order(trader, "PENDING", Instant.now().minus(Duration.ofMinutes(30)), false);
        when(sepay.orderDetail(code)).thenReturn(Optional.empty());

        as(admin, post(ORDERS + "/" + code + "/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("EXPIRED"));
    }

    @Test
    void NSF13_anUnreachableGateway_isA503_andLeavesTheOrderPending() throws Exception {
        String code = order(trader, "PENDING", Instant.now().minus(Duration.ofMinutes(30)), false);
        when(sepay.orderDetail(anyString())).thenThrow(new GatewayUnavailableException("timeout"));

        as(admin, post(ORDERS + "/" + code + "/reconcile"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENT_GATEWAY_UNAVAILABLE"));

        assertThat(statusOf(code)).isEqualTo("PENDING");
        assertThat(audits("ORDER_RECONCILED", code)).isZero();
    }

    @Test
    void UC48_aFlaggedOrder_isHandledOnce_withANote_andAudited() throws Exception {
        String flagged = order(trader, "PAID", Instant.now(), true);
        String plain = order(trader, "PAID", Instant.now(), false);

        as(admin, handled(flagged, "  ")).andExpect(status().isBadRequest());
        as(admin, handled(flagged, "Refunded 99,000 VND by bank transfer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.handledAt").exists())
                .andExpect(jsonPath("$.handledBy").value(admin.toString()))
                .andExpect(jsonPath("$.handlingNote").value("Refunded 99,000 VND by bank transfer"));
        as(admin, handled(flagged, "again"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_AWAITING_HANDLING"));
        as(admin, handled(plain, "nothing to handle")).andExpect(status().isConflict());
        as(admin, handled("CP-NO-SUCH-ORDER", "x")).andExpect(status().isNotFound());

        assertThat(audits("ORDER_HANDLED", flagged)).isEqualTo(1);
    }

    private MockHttpServletRequestBuilder handled(String code, String note) {
        return post(ORDERS + "/" + code + "/handled")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\": \"" + note + "\"}");
    }

    /** An order of PRO_MONTHLY (99,000 VND) through SePay, payable for 15 minutes from {@code createdAt}. */
    private String order(UUID user, String status, Instant createdAt, boolean manual) {
        String code = "CPT"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 15).toUpperCase();
        Timestamp at = Timestamp.from(createdAt);
        sql.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, paid_at, created_at, expires_at,
                                                        updated_at, manual_handling)
                        select ?, ?, package_id, ?, price_amount, 'VND', 'SEPAY', ?, ?, ?, ?, ?, ?
                          from subscription_package where package_id = ?""")
                .params(
                        UUID.randomUUID(),
                        user,
                        code,
                        status,
                        "PAID".equals(status) ? at : null,
                        at,
                        Timestamp.from(createdAt.plus(Duration.ofMinutes(15))),
                        at,
                        manual,
                        PRO_MONTHLY)
                .update();
        return code;
    }

    private String statusOf(String code) {
        return sql.sql("select order_status from subscription_order where order_code = ?")
                .param(code)
                .query(String.class)
                .single();
    }

    /** Audit rows are written through JPA; flushed first, since this transaction reads them with plain SQL. */
    private long audits(String action, String code) {
        entityManager.flush();
        return sql.sql("""
                        select count(*) from audit_log a join subscription_order o on o.order_id = a.entity_id
                         where a.user_id = ? and a.action_code = ? and a.entity_type = 'SUBSCRIPTION_ORDER'
                           and o.order_code = ?""").params(admin, action, code).query(Long.class).single();
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(user)));
    }

    private UUID account(UserRole role) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@uc48.invalid", role.name(), at, at).update();
        return id;
    }

    private String bearer(UUID userId) {
        String role = sql.sql("select role from user_account where user_id = ?")
                .param(userId)
                .query(String.class)
                .single();
        Instant issued = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(issued)
                .expiresAt(issued.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, role)
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }
}
