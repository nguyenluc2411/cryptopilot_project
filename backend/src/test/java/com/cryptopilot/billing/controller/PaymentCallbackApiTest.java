package com.cryptopilot.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.support.TestcontainersConfig;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** The SePay IPN end to end: open to the gateway, authenticated by its key, settling the order (BR-53, NSF-12). */
@SpringBootTest(
        properties = {
            "cryptopilot.billing.sepay.merchant-id=MERCHANT_TEST",
            "cryptopilot.billing.sepay.secret-key=spsk_test_secret",
            "cryptopilot.billing.sepay.ipn-secret-key=ipn-test-secret"
        })
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class PaymentCallbackApiTest {

    private static final String IPN = "/api/v1/billing/ipn/sepay";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private EntityManager entityManager;

    @Test
    void BR53_theIpn_needsNoSessionAndSettlesTheOrder() throws Exception {
        UUID trader = trader();
        String order = pendingOrder(trader);

        mvc.perform(ipn("ipn-test-secret", paid(order, "99000.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        entityManager.flush();

        assertThat(statusOf(order)).isEqualTo("PAID");
        assertThat(sql.sql(
                                "select count(*) from user_subscription where user_id = ? and subscription_status = 'ACTIVE'")
                        .param(trader)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void BR53_anIpnWithoutTheIpnSecretKey_isRefusedAndReadNoFurther() throws Exception {
        String order = pendingOrder(trader());

        mvc.perform(ipn(null, paid(order, "99000"))).andExpect(status().isUnauthorized());
        mvc.perform(ipn("spsk_test_secret", paid(order, "99000"))).andExpect(status().isUnauthorized());
        entityManager.flush();

        assertThat(statusOf(order)).isEqualTo("PENDING");
    }

    @Test
    void NSF12_anAuthenticBodyThatIsNotANotification_isABadRequest() throws Exception {
        mvc.perform(ipn("ipn-test-secret", "{\"hello\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    /** A void is acknowledged, so SePay stops sending it, and changes nothing. */
    @Test
    void NSF12_aVoid_isAcknowledgedAndChangesNothing() throws Exception {
        String order = pendingOrder(trader());

        mvc.perform(ipn("ipn-test-secret", paid(order, "99000").replace("ORDER_PAID", "TRANSACTION_VOID")))
                .andExpect(status().isOk());
        entityManager.flush();

        assertThat(statusOf(order)).isEqualTo("PENDING");
    }

    private static RequestBuilder ipn(String key, String body) {
        var request = post(IPN).contentType(MediaType.APPLICATION_JSON).content(body);
        return key == null ? request : request.header("X-Secret-Key", key);
    }

    private static String paid(String order, String amount) {
        return """
                {"timestamp": 1759134682, "notification_type": "ORDER_PAID",
                 "order": {"order_status": "CAPTURED", "order_currency": "VND", "order_amount": "%s",
                           "order_invoice_number": "%s"},
                 "transaction": {"transaction_id": "68da43da2d9de", "transaction_status": "APPROVED"},
                 "customer": null}""".formatted(amount, order);
    }

    private String statusOf(String order) {
        return sql.sql("select order_status from subscription_order where order_code = ?")
                .param(order)
                .query(String.class)
                .single();
    }

    private String pendingOrder(UUID trader) {
        String code = "CP"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, created_at, expires_at,
                                                        updated_at)
                        values (?, ?, '019b76da-a800-7002-8000-000000000002', ?, 99000, 'VND', 'SEPAY', 'PENDING',
                                ?, ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        trader,
                        code,
                        now,
                        Timestamp.from(Instant.now().plusSeconds(900)),
                        now)
                .update();
        return code;
    }

    private UUID trader() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@t069.invalid", now, now).update();
        return id;
    }
}
