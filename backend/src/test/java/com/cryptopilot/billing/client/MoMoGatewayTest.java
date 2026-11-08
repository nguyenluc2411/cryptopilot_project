package com.cryptopilot.billing.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.billing.calculator.MoMoSignature;
import com.cryptopilot.billing.model.Checkout;
import com.cryptopilot.billing.model.CheckoutRequest;
import com.cryptopilot.billing.model.GatewayLookup;
import com.cryptopilot.billing.model.PaymentResult;
import com.cryptopilot.billing.model.enums.GatewayCode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MoMo v2 protocol of D-146 with MoMo's public sandbox keys. The signatures were computed apart from this code
 * (Python hmac), and the query answers are the ones the test environment gave on 2026-11-08.
 */
class MoMoGatewayTest {

    private static final MoMoProperties SANDBOX = new MoMoProperties(
            "MOMO",
            "F8BBA842ECF85",
            "K951B6PE1waDMi640xX08PD3vg6EkVlz",
            URI.create("https://test-payment.momo.vn"),
            "https://api.example.test/api/v1/billing/ipn/momo");

    private static final CheckoutRequest REQUEST = new CheckoutRequest(
            "CP261108030000ABCD",
            new BigDecimal("99000"),
            "CryptoPilot PRO_MONTHLY CP261108030000ABCD",
            URI.create("https://app.example.test/subscription/payment-result?orderCode=CP261108030000ABCD"),
            Instant.parse("2026-11-08T03:15:00Z"));

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T03:00:00Z"), ZoneOffset.UTC);

    /** What the test environment answered a create. */
    private static final String CREATED = """
            {"partnerCode":"MOMO","orderId":"CP261108030000ABCD","requestId":"CP261108030000ABCD","amount":99000,
             "responseTime":1794144312797,"resultCode":0,"message":"Thành công.",
             "payUrl":"https://test-payment.momo.vn/v2/gateway/pay?t=TU9NTw&amp;s=9071f6e1"}""";

    /** What the test environment answered a query about an order opened and not paid. */
    private static final String AWAITING = """
            {"partnerCode":"MOMO","orderId":"CP261108030000ABCD","requestId":"CP261108030000ABCD-Q1","extraData":"",
             "amount":99000,"transId":0,"payType":"","resultCode":1000,"refundTrans":[],
             "message":"Giao dịch đã được khởi tạo, chờ người dùng xác nhận thanh toán.",
             "responseTime":1794144312845,"lastUpdated":1794144312795}""";

    /** What the test environment answered a query about an order it does not know. */
    private static final String UNKNOWN = """
            {"partnerCode":"MOMO","orderId":"CP-NO-SUCH","requestId":"X","responseTime":1794144312905,
             "resultCode":1005,"message":"Transaction expired or did not exist."}""";

    /** A paid IPN, signed with the sandbox secret key apart from this code. */
    private static final String PAID_IPN = """
            {"partnerCode":"MOMO","orderId":"CP261108030000ABCD","requestId":"CP261108030000ABCD","amount":99000,
             "orderInfo":"CryptoPilot PRO_MONTHLY CP261108030000ABCD","orderType":"momo_wallet",
             "transId":4088878653,"resultCode":0,"message":"Thanh cong.","payType":"qr",
             "responseTime":1794144312797,"extraData":"",
             "signature":"6c14bece825fa704de676e1d0488fbb214731ecb0780713926331e16474a4742"}""";

    @Test
    void D146_aCreateRequest_isSignedInMoMosOrder() {
        Map<String, String> fields = new HashMap<>();
        fields.put("accessKey", "F8BBA842ECF85");
        fields.put("amount", "99000");
        fields.put("extraData", "");
        fields.put("ipnUrl", SANDBOX.ipnUrl());
        fields.put("orderId", REQUEST.orderCode());
        fields.put("orderInfo", REQUEST.description());
        fields.put("partnerCode", "MOMO");
        fields.put("redirectUrl", REQUEST.returnUrl().toString());
        fields.put("requestId", REQUEST.orderCode());
        fields.put("requestType", "captureWallet");

        assertThat(MoMoSignature.sign(MoMoSignature.CREATE, fields, SANDBOX.secretKey()))
                .isEqualTo("e36922fb277fda60cef98d28cdbcc48402aadd0ef39b678a363abd906f3e7b9d");
    }

    @Test
    void D146_theCheckout_postsTheSignedCreate_andSendsTheBrowserToThePayUrl() {
        Recording api = new Recording(CREATED);

        Checkout checkout = gateway(api).checkout(REQUEST);

        assertThat(checkout.method()).isEqualTo("GET");
        assertThat(checkout.url()).hasToString("https://test-payment.momo.vn/v2/gateway/pay?t=TU9NTw&s=9071f6e1");
        assertThat(api.paths).containsExactly(MoMoGateway.CREATE);
        Map<String, Object> sent = api.requests.getFirst();
        assertThat(sent)
                .containsEntry("partnerCode", "MOMO")
                .containsEntry("amount", 99000L)
                .containsEntry("orderId", "CP261108030000ABCD")
                .containsEntry("requestType", "captureWallet")
                .containsEntry("ipnUrl", SANDBOX.ipnUrl())
                .containsEntry("signature", "e36922fb277fda60cef98d28cdbcc48402aadd0ef39b678a363abd906f3e7b9d");
    }

    @Test
    void D146_aRefusedCreate_isAnUnavailableGateway() {
        assertThatExceptionOfType(GatewayUnavailableException.class)
                .isThrownBy(() -> gateway(new Recording("{\"resultCode\":41,\"message\":\"Duplicate\"}"))
                        .checkout(REQUEST));
        assertThatExceptionOfType(GatewayUnavailableException.class)
                .isThrownBy(() -> gateway(new Recording("<html>")).checkout(REQUEST));
    }

    @Test
    void NSF13_theQueryAnswers_areReadAsTheTestEnvironmentGivesThem() {
        assertThat(gateway(new Recording(AWAITING)).lookup("CP261108030000ABCD"))
                .isEqualTo(GatewayLookup.of(GatewayLookup.State.AWAITING));
        assertThat(gateway(new Recording(UNKNOWN)).lookup("CP-NO-SUCH"))
                .isEqualTo(GatewayLookup.of(GatewayLookup.State.UNKNOWN));
        assertThat(gateway(new Recording("{\"resultCode\":1006}")).lookup("CP1"))
                .isEqualTo(GatewayLookup.of(GatewayLookup.State.CANCELLED));
        assertThatExceptionOfType(GatewayUnavailableException.class)
                .isThrownBy(() -> gateway(new Recording("{}")).lookup("CP1"));
    }

    @Test
    void NSF13_aPaidQuery_isAPaymentOfItsAmount_signedWithAFreshRequestId() {
        Recording api = new Recording("{\"resultCode\":0,\"amount\":99000,\"transId\":4088878653}");

        GatewayLookup lookup = gateway(api).lookup("CP261108030000ABCD");

        assertThat(lookup.state()).isEqualTo(GatewayLookup.State.PAID);
        assertThat(lookup.payment().amount()).isEqualByComparingTo("99000");
        assertThat(lookup.payment().transactionNo()).isEqualTo("4088878653");
        Map<String, Object> sent = api.requests.getFirst();
        assertThat(api.paths).containsExactly(MoMoGateway.QUERY);
        assertThat(sent.get("requestId")).isEqualTo("CP261108030000ABCD-Q" + CLOCK.millis());
        assertThat(sent.get("signature"))
                .isEqualTo(MoMoSignature.sign(
                        MoMoSignature.QUERY,
                        Map.of(
                                "accessKey", "F8BBA842ECF85",
                                "orderId", "CP261108030000ABCD",
                                "partnerCode", "MOMO",
                                "requestId", (String) sent.get("requestId")),
                        SANDBOX.secretKey()));
    }

    @Test
    void BR53_aSignedPaidIpn_isAPaymentOfItsAmount() {
        Optional<PaymentResult> result = gateway(new Recording("")).interpret(PAID_IPN);

        assertThat(result).hasValueSatisfying(paid -> {
            assertThat(paid.gateway()).isEqualTo(GatewayCode.MOMO);
            assertThat(paid.orderCode()).isEqualTo("CP261108030000ABCD");
            assertThat(paid.paid()).isTrue();
            assertThat(paid.amount()).isEqualByComparingTo("99000");
            assertThat(paid.transactionNo()).isEqualTo("4088878653");
            assertThat(paid.responseCode()).isEqualTo("0");
        });
    }

    @Test
    void BR53_anIpnWithAnotherSignatureOrPartner_isRefused() {
        MoMoGateway momo = gateway(new Recording(""));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> momo.interpret(PAID_IPN.replace("\"amount\":99000", "\"amount\":1000")));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> momo.interpret(PAID_IPN.replace("\"partnerCode\":\"MOMO\"", "\"partnerCode\":\"X\"")));
        assertThatIllegalArgumentException().isThrownBy(() -> momo.interpret("not json"));
    }

    @Test
    void NSF12_aFailedIpn_failsTheOrder_andAnAwaitingOrExpiredOne_settlesNothing() {
        MoMoGateway momo = gateway(new Recording(""));

        assertThat(momo.interpret(signedIpn(1006))).hasValueSatisfying(failed -> {
            assertThat(failed.paid()).isFalse();
            assertThat(failed.responseCode()).isEqualTo("1006");
        });
        assertThat(momo.interpret(signedIpn(1000))).isEmpty();
        assertThat(momo.interpret(signedIpn(1005))).isEmpty();
    }

    @Test
    void D146_withoutKeysOrAnIpnAddress_MoMoIsUnavailable_andCancellingStopsNothing() {
        MoMoGateway unconfigured = new MoMoGateway(
                new MoMoProperties("MOMO", "F8BBA842ECF85", "K951B6PE1waDMi640xX08PD3vg6EkVlz", SANDBOX.endpoint(), ""),
                new Recording(""),
                JsonMapper.builder().build(),
                CLOCK);

        assertThat(unconfigured.configured()).isFalse();
        assertThatExceptionOfType(GatewayUnavailableException.class).isThrownBy(() -> unconfigured.checkout(REQUEST));
        assertThat(gateway(new Recording("")).cancel("CP1")).isTrue();
        assertThat(gateway(new Recording("")).code()).isEqualTo(GatewayCode.MOMO);
    }

    /** An IPN with this result code, signed with the sandbox key by the code under test. */
    private static String signedIpn(int resultCode) {
        Map<String, String> fields = new HashMap<>();
        fields.put("accessKey", "F8BBA842ECF85");
        fields.put("amount", "99000");
        fields.put("extraData", "");
        fields.put("message", "x");
        fields.put("orderId", "CP1");
        fields.put("orderInfo", "i");
        fields.put("orderType", "momo_wallet");
        fields.put("partnerCode", "MOMO");
        fields.put("payType", "qr");
        fields.put("requestId", "CP1");
        fields.put("responseTime", "1");
        fields.put("resultCode", String.valueOf(resultCode));
        fields.put("transId", "0");
        String signature = MoMoSignature.sign(MoMoSignature.IPN, fields, SANDBOX.secretKey());
        return """
                {"partnerCode":"MOMO","orderId":"CP1","requestId":"CP1","amount":99000,"orderInfo":"i",
                 "orderType":"momo_wallet","transId":0,"resultCode":%d,"message":"x","payType":"qr",
                 "responseTime":1,"extraData":"","signature":"%s"}""".formatted(resultCode, signature);
    }

    private static MoMoGateway gateway(MoMoApi api) {
        return new MoMoGateway(SANDBOX, api, JsonMapper.builder().build(), CLOCK);
    }

    /** MoMo without a network: records each request and answers the same text. */
    private static final class Recording implements MoMoApi {

        private final String answer;
        private final List<String> paths = new ArrayList<>();
        private final List<Map<String, Object>> requests = new ArrayList<>();

        private Recording(String answer) {
            this.answer = answer;
        }

        @Override
        public String post(String path, Map<String, Object> request) {
            paths.add(path);
            requests.add(request);
            return answer;
        }
    }
}
