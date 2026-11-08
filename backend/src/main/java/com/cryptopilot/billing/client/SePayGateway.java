package com.cryptopilot.billing.client;

import com.cryptopilot.billing.calculator.SePaySignature;
import com.cryptopilot.billing.model.Checkout;
import com.cryptopilot.billing.model.CheckoutRequest;
import com.cryptopilot.billing.model.GatewayLookup;
import com.cryptopilot.billing.model.PaymentResult;
import com.cryptopilot.billing.model.enums.GatewayCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SePay Payment Gateway (D-146), its documented protocol only:
 *
 * <ul>
 *   <li>Checkout: the browser posts a form to {@code /v1/checkout/init} with the fields in the documented order and
 *       a {@link SePaySignature signature} under the merchant secret key. The three return addresses are the same
 *       result page, which reads the outcome from the order, never from the address (SCR-31).
 *   <li>IPN: SePay posts JSON with the IPN secret key in {@code X-Secret-Key}. {@code ORDER_PAID} with order status
 *       {@code CAPTURED} is a payment of {@code order.order_amount} for {@code order.order_invoice_number}, which is
 *       our order code. Any other notification (a void) changes nothing here and is left to an administrator.
 * </ul>
 *
 * <p>Rule: BR-53; TECHNICAL_DESIGN 7.11; D-146. Reference: SePay developer documentation, "Cổng thanh toán" (2025).
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SePayGateway implements PaymentGateway {

    /** The one SePay operation used: a one-time purchase. */
    static final String PURCHASE = "PURCHASE";

    /** The notification of a captured payment. */
    static final String ORDER_PAID = "ORDER_PAID";

    /** The order status of a paid order. */
    static final String CAPTURED = "CAPTURED";

    private static final Logger log = LoggerFactory.getLogger(SePayGateway.class);

    /** The order status of an order opened and not paid yet. */
    static final String AWAITING = "AUTHENTICATION_NOT_NEEDED";

    /** The order status of an order cancelled on SePay's page. */
    static final String CANCELLED = "CANCELLED";

    private final SePayProperties properties;
    private final JsonMapper json;
    private final SePayOrderApi orders;

    @Override
    public GatewayCode code() {
        return GatewayCode.SEPAY;
    }

    @Override
    public boolean configured() {
        return properties.configured();
    }

    @Override
    public Checkout checkout(CheckoutRequest request) {
        if (!configured()) {
            throw new GatewayUnavailableException("SePay is not configured on this deployment");
        }
        String back = request.returnUrl().toString();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("order_amount", request.amount().toBigIntegerExact().toString());
        fields.put("merchant", properties.merchantId());
        fields.put("currency", "VND");
        fields.put("operation", PURCHASE);
        fields.put("order_description", request.description());
        fields.put("order_invoice_number", request.orderCode());
        fields.put("success_url", back);
        fields.put("error_url", back);
        fields.put("cancel_url", back);
        fields.put("lang", "vi");
        fields.put("signature", SePaySignature.sign(fields, properties.secretKey()));
        return Checkout.form(properties.checkoutUrl(), fields);
    }

    /**
     * What SePay's order query says of the order: {@code data.order_status} CAPTURED is a payment of
     * {@code data.order_amount}, CANCELLED a cancelled page, AUTHENTICATION_NOT_NEEDED (or any status the
     * documentation does not list) an order still awaiting payment, and a 404 an order SePay never saw.
     *
     * @throws GatewayUnavailableException when SePay cannot be reached, refuses the query or answers something else
     */
    @Override
    public GatewayLookup lookup(String orderCode) {
        Optional<String> answer = orders.orderDetail(orderCode);
        if (answer.isEmpty()) {
            return GatewayLookup.of(GatewayLookup.State.UNKNOWN);
        }
        JsonNode data;
        try {
            data = json.readTree(answer.get()).path("data");
        } catch (JacksonException e) {
            throw new GatewayUnavailableException("SePay answered about " + orderCode + " with no JSON", e);
        }
        String status = data.path("order_status").asString(null);
        if (status == null) {
            throw new GatewayUnavailableException("SePay answered about " + orderCode + " with no order status");
        }
        if (CANCELLED.equals(status)) {
            return GatewayLookup.of(GatewayLookup.State.CANCELLED);
        }
        if (!CAPTURED.equals(status)) {
            if (!AWAITING.equals(status)) {
                log.warn("SePay order {} has the undocumented status {}; read as awaiting payment", orderCode, status);
            }
            return GatewayLookup.of(GatewayLookup.State.AWAITING);
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(data.path("order_amount").asString(""));
        } catch (NumberFormatException e) {
            throw new GatewayUnavailableException("SePay says " + orderCode + " is paid but names no amount", e);
        }
        JsonNode transaction = data.path("transactions").path(0);
        return GatewayLookup.paid(new PaymentResult(
                GatewayCode.SEPAY,
                orderCode,
                true,
                amount,
                transaction.path("id").asString(null),
                status,
                answer.get()));
    }

    /** Whether an IPN carries this deployment's IPN secret key, compared in constant time. */
    public boolean authentic(String secretKeyHeader) {
        if (!configured() || secretKeyHeader == null) {
            return false;
        }
        return MessageDigest.isEqual(
                properties.ipnSecretKey().getBytes(StandardCharsets.UTF_8),
                secretKeyHeader.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The payment an IPN reports, or empty for a notification that settles nothing.
     *
     * @throws IllegalArgumentException when the body is not a SePay notification
     */
    public Optional<PaymentResult> interpret(String body) {
        JsonNode root;
        try {
            root = json.readTree(body == null ? "" : body);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("the SePay IPN is not JSON", e);
        }
        JsonNode order = root.path("order");
        String invoice = order.path("order_invoice_number").asString(null);
        String type = root.path("notification_type").asString(null);
        if (invoice == null || type == null) {
            throw new IllegalArgumentException("the SePay IPN names no order or no notification type");
        }
        String status = order.path("order_status").asString(null);
        if (!ORDER_PAID.equals(type) || !CAPTURED.equals(status)) {
            log.warn("SePay {} for order {} (status {}) settles nothing", type, invoice, status);
            return Optional.empty();
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(order.path("order_amount").asString(""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("the SePay IPN for " + invoice + " has no amount", e);
        }
        return Optional.of(new PaymentResult(
                GatewayCode.SEPAY,
                invoice,
                true,
                amount,
                root.path("transaction").path("transaction_id").asString(null),
                status,
                body));
    }
}
