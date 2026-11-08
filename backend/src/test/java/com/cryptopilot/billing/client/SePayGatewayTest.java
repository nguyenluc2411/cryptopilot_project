package com.cryptopilot.billing.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.billing.calculator.SePaySignature;
import com.cryptopilot.billing.model.Checkout;
import com.cryptopilot.billing.model.CheckoutRequest;
import com.cryptopilot.billing.model.GatewayLookup;
import com.cryptopilot.billing.model.PaymentResult;
import com.cryptopilot.billing.model.enums.GatewayCode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The SePay protocol of D-146: the checkout form, the IPN key and the IPN body, from the documented examples. */
class SePayGatewayTest {

    private static final SePayProperties SANDBOX = new SePayProperties(
            "MERCHANT_123",
            "spsk_test_secret",
            "ipn-secret",
            URI.create("https://pay-sandbox.sepay.vn/v1/checkout/init"),
            URI.create("https://pgapi-sandbox.sepay.vn"));

    private static final SePayGateway GATEWAY = answering(Optional.empty());

    private static final CheckoutRequest REQUEST = new CheckoutRequest(
            "CP261108030000ABCD",
            new BigDecimal("99000"),
            "CryptoPilot PRO_MONTHLY CP261108030000ABCD",
            URI.create("https://app.example.test/subscription/payment-result?orderCode=CP261108030000ABCD"),
            Instant.parse("2026-11-08T03:15:00Z"));

    /** The documentation's quick-start IPN, with our order code as the invoice number. */
    private static final String PAID = """
            {"timestamp": 1759134682, "notification_type": "ORDER_PAID",
             "order": {"id": "e2c195be-c721-47eb-b323-99ab24e52d85", "order_id": "NQD-68DA43D73C1A5",
                       "order_status": "CAPTURED", "order_currency": "VND", "order_amount": "99000.00",
                       "order_invoice_number": "CP261108030000ABCD", "custom_data": [],
                       "order_description": "CryptoPilot PRO_MONTHLY CP261108030000ABCD"},
             "transaction": {"id": "384c66dd-41e6-4316-a544-b4141682595c", "payment_method": "BANK_TRANSFER",
                             "transaction_id": "68da43da2d9de", "transaction_type": "PAYMENT",
                             "transaction_status": "APPROVED", "transaction_amount": "99000",
                             "transaction_currency": "VND"},
             "customer": null, "agreement": null}""";

    @Test
    void D146_theCheckout_isTheDocumentedFormPostedToTheConfiguredEndpoint() {
        Checkout checkout = GATEWAY.checkout(REQUEST);

        assertThat(checkout.method()).isEqualTo("POST");
        assertThat(checkout.url()).hasToString("https://pay-sandbox.sepay.vn/v1/checkout/init");
        assertThat(checkout.fields().keySet())
                .containsExactly(
                        "order_amount",
                        "merchant",
                        "currency",
                        "operation",
                        "order_description",
                        "order_invoice_number",
                        "success_url",
                        "error_url",
                        "cancel_url",
                        "lang",
                        "signature");
        assertThat(checkout.fields())
                .containsEntry("order_amount", "99000")
                .containsEntry("merchant", "MERCHANT_123")
                .containsEntry("currency", "VND")
                .containsEntry("operation", "PURCHASE")
                .containsEntry("order_invoice_number", "CP261108030000ABCD")
                .containsEntry("success_url", REQUEST.returnUrl().toString())
                .containsEntry("cancel_url", REQUEST.returnUrl().toString());
    }

    @Test
    void D146_theCheckoutSignature_coversTheSignedFieldsUnderTheMerchantSecret() {
        Map<String, String> fields =
                new LinkedHashMap<>(GATEWAY.checkout(REQUEST).fields());
        String signature = fields.remove("signature");

        assertThat(signature).isEqualTo(SePaySignature.sign(fields, "spsk_test_secret"));
    }

    @Test
    void D146_withoutCredentials_SePayIsUnavailable() {
        SePayGateway unconfigured = new SePayGateway(
                new SePayProperties("", "", "", SANDBOX.checkoutUrl(), SANDBOX.apiUrl()),
                JsonMapper.builder().build(),
                code -> Optional.empty());

        assertThat(unconfigured.configured()).isFalse();
        assertThat(unconfigured.authentic("")).isFalse();
        assertThatExceptionOfType(GatewayUnavailableException.class).isThrownBy(() -> unconfigured.checkout(REQUEST));
    }

    @Test
    void BR53_anIpn_isAuthenticOnlyWithTheIpnSecretKey() {
        assertThat(GATEWAY.code()).isEqualTo(GatewayCode.SEPAY);
        assertThat(GATEWAY.authentic("ipn-secret")).isTrue();
        assertThat(GATEWAY.authentic("spsk_test_secret"))
                .as("the merchant key is not the IPN key")
                .isFalse();
        assertThat(GATEWAY.authentic("ipn-secre")).isFalse();
        assertThat(GATEWAY.authentic(null)).isFalse();
    }

    @Test
    void NSF12_anOrderPaidIpn_isAPaymentOfTheOrderAmount() {
        PaymentResult result = GATEWAY.interpret(PAID).orElseThrow();

        assertThat(result.gateway()).isEqualTo(GatewayCode.SEPAY);
        assertThat(result.orderCode()).isEqualTo("CP261108030000ABCD");
        assertThat(result.paid()).isTrue();
        assertThat(result.amount()).isEqualByComparingTo("99000");
        assertThat(result.transactionNo()).isEqualTo("68da43da2d9de");
        assertThat(result.responseCode()).isEqualTo("CAPTURED");
        assertThat(result.payload()).isEqualTo(PAID);
    }

    @Test
    void NSF12_aVoidOrAnOrderNotCaptured_settlesNothing() {
        assertThat(GATEWAY.interpret(PAID.replace("ORDER_PAID", "TRANSACTION_VOID")))
                .isEmpty();
        assertThat(GATEWAY.interpret(PAID.replace("\"CAPTURED\"", "\"CANCELLED\"")))
                .isEmpty();
    }

    @Test
    void NSF12_aBodyThatIsNotASePayNotification_isRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> GATEWAY.interpret("not json"));
        assertThatIllegalArgumentException().isThrownBy(() -> GATEWAY.interpret(null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GATEWAY.interpret("{\"notification_type\":\"ORDER_PAID\"}"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GATEWAY.interpret(PAID.replace("\"99000.00\"", "\"lots\"")));
    }

    /** The documentation's order detail example, with our order code and amount. */
    private static String detail(String status) {
        return """
                {"data": {"id": "6f1c2b3a-8d4e-11f0-9a1b-0242ac120002", "customer_id": null,
                          "order_id": "SEPAY-68B01673A77FF", "order_invoice_number": "CP261108030000ABCD",
                          "order_status": "%s", "order_amount": "99000.00", "order_currency": "VND",
                          "transactions": [{"id": "7a2d3c4b-8d4e-11f0-9a1b-0242ac120002",
                                            "payment_method": "BANK_TRANSFER", "transaction_type": "PAYMENT",
                                            "transaction_amount": "99000", "transaction_status": "APPROVED"}]}}""".formatted(status);
    }

    private static SePayGateway answering(Optional<String> answer) {
        return new SePayGateway(SANDBOX, JsonMapper.builder().build(), code -> answer);
    }

    @Test
    void NSF13_aCapturedOrder_isAPaymentOfItsOrderAmount() {
        String body = detail("CAPTURED");

        GatewayLookup lookup = answering(Optional.of(body)).lookup("CP261108030000ABCD");

        assertThat(lookup.state()).isEqualTo(GatewayLookup.State.PAID);
        assertThat(lookup.payment().paid()).isTrue();
        assertThat(lookup.payment().gateway()).isEqualTo(GatewayCode.SEPAY);
        assertThat(lookup.payment().orderCode()).isEqualTo("CP261108030000ABCD");
        assertThat(lookup.payment().amount()).isEqualByComparingTo("99000");
        assertThat(lookup.payment().transactionNo()).isEqualTo("7a2d3c4b-8d4e-11f0-9a1b-0242ac120002");
        assertThat(lookup.payment().responseCode()).isEqualTo("CAPTURED");
        assertThat(lookup.payment().payload()).isEqualTo(body);
    }

    @Test
    void NSF13_aCapturedOrderWithoutTransactions_isStillPaid() {
        String body = "{\"data\": {\"order_status\": \"CAPTURED\", \"order_amount\": \"99000.00\"}}";

        GatewayLookup lookup = answering(Optional.of(body)).lookup("CP1");

        assertThat(lookup.state()).isEqualTo(GatewayLookup.State.PAID);
        assertThat(lookup.payment().transactionNo()).isNull();
    }

    @Test
    void NSF13_cancelledAwaitingUndocumentedAndUnknownOrders_settleNothing() {
        assertThat(answering(Optional.of(detail("CANCELLED"))).lookup("CP1"))
                .isEqualTo(GatewayLookup.of(GatewayLookup.State.CANCELLED));
        assertThat(answering(Optional.of(detail("AUTHENTICATION_NOT_NEEDED"))).lookup("CP1"))
                .isEqualTo(GatewayLookup.of(GatewayLookup.State.AWAITING));
        assertThat(answering(Optional.of(detail("SOMETHING_NEW"))).lookup("CP1"))
                .isEqualTo(GatewayLookup.of(GatewayLookup.State.AWAITING));
        assertThat(answering(Optional.empty()).lookup("CP1")).isEqualTo(GatewayLookup.of(GatewayLookup.State.UNKNOWN));
    }

    @Test
    void NSF13_anAnswerThatIsNotAnOrder_isAnUnavailableGateway() {
        assertThatExceptionOfType(GatewayUnavailableException.class)
                .isThrownBy(() -> answering(Optional.of("<html>")).lookup("CP1"));
        assertThatExceptionOfType(GatewayUnavailableException.class)
                .isThrownBy(() -> answering(Optional.of("{\"data\": {}}")).lookup("CP1"));
        assertThatExceptionOfType(GatewayUnavailableException.class).isThrownBy(() -> answering(
                        Optional.of("{\"data\": {\"order_status\": \"CAPTURED\", \"order_amount\": \"\"}}"))
                .lookup("CP1"));
    }
}
