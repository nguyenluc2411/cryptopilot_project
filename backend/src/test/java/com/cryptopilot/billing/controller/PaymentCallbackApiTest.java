package com.cryptopilot.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.billing.calculator.MoMoSignature;
import com.cryptopilot.support.TestcontainersConfig;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
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

/**
 * The SePay and MoMo IPNs end to end: open to the gateway, authenticated by its key or signature, settling the order
 * (BR-53, NSF-12).
 */
@SpringBootTest(
        properties = {
            "cryptopilot.billing.sepay.merchant-id=MERCHANT_TEST",
            "cryptopilot.billing.sepay.secret-key=spsk_test_secret",
            "cryptopilot.billing.sepay.ipn-secret-key=ipn-test-secret",
            "cryptopilot.billing.momo.partner-code=MOMO",
            "cryptopilot.billing.momo.access-key=F8BBA842ECF85",
            "cryptopilot.billing.momo.secret-key=K951B6PE1waDMi640xX08PD3vg6EkVlz",
            "cryptopilot.billing.momo.ipn-url=https://api.example.test/api/v1/billing/ipn/momo"
        })
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class PaymentCallbackApiTest {

    private static final String IPN = "/api/v1/billing/ipn/sepay";
    private static final String MOMO_IPN = "/api/v1/billing/ipn/momo";

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

    @Test
    void BR53_aSignedMoMoIpn_needsNoSession_settlesTheOrder_andIsAnsweredWith204() throws Exception {
        UUID trader = trader();
        String order = pendingOrder(trader, "MOMO");

        mvc.perform(post(MOMO_IPN).contentType(MediaType.APPLICATION_JSON).content(momoIpn(order, 0)))
                .andExpect(status().isNoContent());
        entityManager.flush();

        assertThat(statusOf(order)).isEqualTo("PAID");
    }

    @Test
    void BR53_aMoMoIpnWithAnotherSignature_isABadRequest_andChangesNothing() throws Exception {
        String order = pendingOrder(trader(), "MOMO");
        String forged = momoIpn(order, 0).replace("\"amount\":99000", "\"amount\":1000");

        mvc.perform(post(MOMO_IPN).contentType(MediaType.APPLICATION_JSON).content(forged))
                .andExpect(status().isBadRequest());

        assertThat(statusOf(order)).isEqualTo("PENDING");
    }

    @Test
    void NSF12_aFailedMoMoPayment_failsTheOrder() throws Exception {
        String order = pendingOrder(trader(), "MOMO");

        mvc.perform(post(MOMO_IPN).contentType(MediaType.APPLICATION_JSON).content(momoIpn(order, 1006)))
                .andExpect(status().isNoContent());
        entityManager.flush();

        assertThat(statusOf(order)).isEqualTo("FAILED");
    }

    /** A MoMo IPN about the order for 99,000 VND, signed with the sandbox secret key. */
    private static String momoIpn(String order, int resultCode) {
        Map<String, String> fields = new HashMap<>();
        fields.put("accessKey", "F8BBA842ECF85");
        fields.put("amount", "99000");
        fields.put("extraData", "");
        fields.put("message", "ok");
        fields.put("orderId", order);
        fields.put("orderInfo", "CryptoPilot");
        fields.put("orderType", "momo_wallet");
        fields.put("partnerCode", "MOMO");
        fields.put("payType", "qr");
        fields.put("requestId", order);
        fields.put("responseTime", "1794144312797");
        fields.put("resultCode", String.valueOf(resultCode));
        fields.put("transId", "4088878653");
        String signature = MoMoSignature.sign(MoMoSignature.IPN, fields, "K951B6PE1waDMi640xX08PD3vg6EkVlz");
        return """
                {"partnerCode":"MOMO","orderId":"%s","requestId":"%s","amount":99000,"orderInfo":"CryptoPilot",
                 "orderType":"momo_wallet","transId":4088878653,"resultCode":%d,"message":"ok","payType":"qr",
                 "responseTime":1794144312797,"extraData":"","signature":"%s"}""".formatted(order, order, resultCode, signature);
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
        return pendingOrder(trader, "SEPAY");
    }

    private String pendingOrder(UUID trader, String gateway) {
        String code = "CP"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, created_at, expires_at,
                                                        updated_at)
                        values (?, ?, '019b76da-a800-7002-8000-000000000002', ?, 99000, 'VND', ?, 'PENDING',
                                ?, ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        trader,
                        code,
                        gateway,
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
