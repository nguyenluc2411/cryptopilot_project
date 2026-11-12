package com.cryptopilot.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.billing.calculator.MoMoSignature;
import com.cryptopilot.billing.client.MoMoApi;
import com.cryptopilot.billing.dto.request.CreateOrderRequest;
import com.cryptopilot.billing.model.enums.GatewayCode;
import com.cryptopilot.billing.service.BillingService;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
            "cryptopilot.billing.sepay.checkout-url=https://pay.example.test/v1/checkout/init",
            "cryptopilot.billing.sepay.api-url=https://api.sepay.example.test",
            "cryptopilot.billing.momo.endpoint=https://momo.example.test",
            "cryptopilot.billing.momo.partner-code=MOMO",
            "cryptopilot.billing.momo.access-key=TESTACCESSKEY",
            "cryptopilot.billing.momo.secret-key=momo-test-secret",
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

    @Autowired
    private BillingService billing;

    @MockitoBean
    private MoMoApi momoApi;

    /** DS-04a: a paid IPN pays the order and opens an ACTIVE subscription of the package's 30 days. */
    @Test
    void DS04a_theIpn_needsNoSession_paysTheOrder_andOpensAnActive30DaySubscription() throws Exception {
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
        assertThat(sql.sql("""
                                select round(extract(epoch from end_at - start_at) / 86400)
                                  from user_subscription where user_id = ?""").param(trader).query(Long.class).single()).isEqualTo(30);
    }

    /** DS-04c: an IPN without this deployment's key changes nothing and is audited. */
    @Test
    void DS04c_anIpnWithoutTheIpnSecretKey_isRefused_leavesThePendingOrder_andIsAudited() throws Exception {
        String order = pendingOrder(trader());
        long before = rejected("SEPAY", "BAD_SECRET_KEY");

        mvc.perform(ipn(null, paid(order, "99000"))).andExpect(status().isUnauthorized());
        mvc.perform(ipn("spsk_test_secret", paid(order, "99000"))).andExpect(status().isUnauthorized());
        entityManager.flush();

        assertThat(statusOf(order)).isEqualTo("PENDING");
        assertThat(rejected("SEPAY", "BAD_SECRET_KEY")).isEqualTo(before + 2);
    }

    /** NSF-12: an authentic IPN for another amount is acknowledged, changes nothing and is audited. */
    @Test
    void NSF12_anIpnForAnotherAmount_leavesThePendingOrder_andIsAudited() throws Exception {
        UUID trader = trader();
        String order = pendingOrder(trader);

        mvc.perform(ipn("ipn-test-secret", paid(order, "1000"))).andExpect(status().isOk());
        entityManager.flush();

        assertThat(statusOf(order)).isEqualTo("PENDING");
        assertThat(sql.sql("select count(*) from user_subscription where user_id = ?")
                        .param(trader)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(sql.sql("""
                        select count(*) from audit_log
                         where action_code = 'IPN_REJECTED' and new_value ->> 'orderCode' = ?
                           and new_value ->> 'reason' = 'AMOUNT_MISMATCH'""").param(order).query(Long.class).single()).isEqualTo(1);
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

    /** DS-04c: a forged MoMo IPN is a bad request, changes nothing and is audited. */
    @Test
    void DS04c_aMoMoIpnWithAForgedSignature_isABadRequest_leavesThePendingOrder_andIsAudited() throws Exception {
        String order = pendingOrder(trader(), "MOMO");
        String forged = momoIpn(order, 0).replace("\"amount\":99000", "\"amount\":1000");
        long before = rejected("MOMO", "BAD_SIGNATURE");

        mvc.perform(post(MOMO_IPN).contentType(MediaType.APPLICATION_JSON).content(forged))
                .andExpect(status().isBadRequest());
        entityManager.flush();

        assertThat(statusOf(order)).isEqualTo("PENDING");
        assertThat(rejected("MOMO", "BAD_SIGNATURE")).isEqualTo(before + 1);
    }

    /** DS-04b: the Trader cancels on the MoMo page; MoMo's failure result fails the order. */
    @Test
    void DS04b_aCancelledMoMoPayment_failsTheOrder() throws Exception {
        String order = pendingOrder(trader(), "MOMO");

        mvc.perform(post(MOMO_IPN).contentType(MediaType.APPLICATION_JSON).content(momoIpn(order, 1006)))
                .andExpect(status().isNoContent());
        entityManager.flush();

        assertThat(statusOf(order)).isEqualTo("FAILED");
    }

    /**
     * BR-54, D-147: a MoMo order MoMo still shows as pending blocks a new order at 14 minutes; at 15 minutes the new
     * order goes ahead and the old one is EXPIRED; a success MoMo reports afterwards stays EXPIRED, flagged, audited.
     */
    @Test
    void BR54_aMoMoOrderStillPending_blocksUntil15Minutes_thenExpires_andALateSuccessIsALatePayment() throws Exception {
        UUID trader = trader();
        String momo = pendingOrder(trader, "MOMO");
        when(momoApi.post(eq("/v2/gateway/api/query"), anyMap())).thenReturn("""
                {"partnerCode":"MOMO","orderId":"%s","resultCode":1000,"message":"pending"}""".formatted(momo));
        CreateOrderRequest next = new CreateOrderRequest("PRO_MONTHLY", GatewayCode.SEPAY);
        setAge(momo, 14);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> billing.createOrder(trader, next))
                .satisfies(refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.ORDER_ALREADY_PENDING));
        assertThat(statusOf(momo)).isEqualTo("PENDING");

        setAge(momo, 15);
        String created = billing.createOrder(trader, next).order().orderCode();
        entityManager.flush();
        assertThat(statusOf(momo)).isEqualTo("EXPIRED");
        assertThat(statusOf(created)).isEqualTo("PENDING");

        mvc.perform(post(MOMO_IPN).contentType(MediaType.APPLICATION_JSON).content(momoIpn(momo, 0)))
                .andExpect(status().isNoContent());
        entityManager.flush();
        assertThat(statusOf(momo)).isEqualTo("EXPIRED");
        assertThat(sql.sql("select manual_handling from subscription_order where order_code = ?")
                        .param(momo)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(sql.sql("""
                        select count(*) from audit_log
                         where action_code = 'LATE_PAYMENT' and new_value ->> 'orderCode' = ?""").param(momo).query(Long.class).single()).isEqualTo(1);
        assertThat(sql.sql("select count(*) from user_subscription where user_id = ?")
                        .param(trader)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** Moves the order's creation {@code minutes} into the past, with its 15-minute window. */
    private void setAge(String order, int minutes) {
        Instant created = Instant.now().minus(java.time.Duration.ofMinutes(minutes));
        sql.sql("update subscription_order set created_at = ?, expires_at = ? where order_code = ?")
                .params(Timestamp.from(created), Timestamp.from(created.plus(java.time.Duration.ofMinutes(15))), order)
                .update();
        entityManager.clear();
    }

    /** A MoMo IPN about the order for 99,000 VND, signed with the test secret key. */
    private static String momoIpn(String order, int resultCode) {
        Map<String, String> fields = new HashMap<>();
        fields.put("accessKey", "TESTACCESSKEY");
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
        String signature = MoMoSignature.sign(MoMoSignature.IPN, fields, "momo-test-secret");
        return """
                {"partnerCode":"MOMO","orderId":"%s","requestId":"%s","amount":99000,"orderInfo":"CryptoPilot",
                 "orderType":"momo_wallet","transId":4088878653,"resultCode":%d,"message":"ok","payType":"qr",
                 "responseTime":1794144312797,"extraData":"","signature":"%s"}""".formatted(order, order, resultCode, signature);
    }

    /** Audit entries of callbacks refused before they reached an order. */
    private long rejected(String gateway, String reason) {
        return sql.sql("""
                        select count(*) from audit_log
                         where action_code = 'IPN_REJECTED' and entity_id is null
                           and new_value ->> 'gateway' = ? and new_value ->> 'reason' = ?""").params(gateway, reason).query(Long.class).single();
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
