package com.cryptopilot.billing.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The SePay checkout signature against the documentation's own example. The expected digests were computed
 * independently (Python {@code hmac}/{@code hashlib}) from the documented signing string.
 */
class SePaySignatureTest {

    /** The documentation's sample form, its fields in the sample's order, signature excluded. */
    private static Map<String, String> documentedForm() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("order_amount", "100000");
        fields.put("merchant", "MERCHANT_123");
        fields.put("currency", "VND");
        fields.put("operation", "PURCHASE");
        fields.put("order_description", "Payment for order #12345");
        fields.put("order_invoice_number", "INV_20231201_001");
        fields.put("success_url", "https://yoursite.com/payment/success");
        fields.put("error_url", "https://yoursite.com/payment/error");
        fields.put("cancel_url", "https://yoursite.com/payment/cancel");
        fields.put("lang", "en");
        return fields;
    }

    @Test
    void D147_theSignedString_isTheDocumentedOne() {
        assertThat(SePaySignature.signedString(documentedForm()))
                .isEqualTo("order_amount=100000,merchant=MERCHANT_123,currency=VND,operation=PURCHASE,"
                        + "order_description=Payment for order #12345,order_invoice_number=INV_20231201_001,"
                        + "success_url=https://yoursite.com/payment/success,"
                        + "error_url=https://yoursite.com/payment/error,"
                        + "cancel_url=https://yoursite.com/payment/cancel");
    }

    @Test
    void D147_theSignature_isTheBase64HmacSha256OfTheSignedString() {
        assertThat(SePaySignature.sign(documentedForm(), "spsk_test_example"))
                .isEqualTo("jryPr1SkCxiKw4I1lFhhUmVY7fMefcCNz6vgAyKvSWY=");
    }

    /** The documented order wins over the order the fields were put in. */
    @Test
    void D147_theFieldOrder_isSePaysNotTheCallers() {
        Map<String, String> reversed = new LinkedHashMap<>();
        documentedForm().entrySet().stream().toList().reversed().forEach(e -> reversed.put(e.getKey(), e.getValue()));

        assertThat(SePaySignature.sign(reversed, "spsk_test_example"))
                .isEqualTo("jryPr1SkCxiKw4I1lFhhUmVY7fMefcCNz6vgAyKvSWY=");
    }

    /** Empty and "0" values are not signed; text is signed as UTF-8. */
    @Test
    void D147_emptyAndZeroValuesAreSkipped_andTextIsUtf8() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("order_amount", "99000");
        fields.put("merchant", "M1");
        fields.put("currency", "VND");
        fields.put("operation", "PURCHASE");
        fields.put("order_description", "Thanh toán gói Pro");
        fields.put("order_invoice_number", "CP261108030000ABCD");
        fields.put("customer_id", "");
        fields.put("payment_method", "0");
        fields.put("success_url", null);

        assertThat(SePaySignature.signedString(fields)).doesNotContain("customer_id", "payment_method", "success_url");
        assertThat(SePaySignature.sign(fields, "k")).isEqualTo("dTET6XvKJDaFazuDLnmpkV/ei75GLDjUASC8OMO7sIQ=");
    }
}
