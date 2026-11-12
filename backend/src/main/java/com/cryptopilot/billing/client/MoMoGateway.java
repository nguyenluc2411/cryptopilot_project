package com.cryptopilot.billing.client;

import com.cryptopilot.billing.calculator.MoMoSignature;
import com.cryptopilot.billing.model.Checkout;
import com.cryptopilot.billing.model.CheckoutRequest;
import com.cryptopilot.billing.model.GatewayLookup;
import com.cryptopilot.billing.model.PaymentResult;
import com.cryptopilot.billing.model.enums.GatewayCode;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HashMap;
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
 * The MoMo Payment Gateway (D-147), test environment only, its v2 protocol:
 *
 * <ul>
 *   <li>Checkout: {@code POST /v2/gateway/api/create} with {@code requestType=captureWallet}; {@code resultCode 0}
 *       answers a {@code payUrl} the browser is sent to. The order code is MoMo's {@code orderId}.
 *   <li>IPN: JSON with a {@link MoMoSignature#IPN signature} under the secret key; {@code resultCode 0} is a payment
 *       of {@code amount}, any other final code a failed payment. MoMo expects HTTP 204.
 *   <li>Query: {@code POST /v2/gateway/api/query}; {@code resultCode} 0 paid, 1000 and 9000 awaiting, 1005 expired or
 *       unknown, anything else failed or cancelled.
 *   <li>Cancel: MoMo has no cancellation of an unpaid order. So cancelling always reports a refusal: within its
 *       15-minute window the order stays PENDING and a new order is refused (D-147); after the window it expires
 *       whatever MoMo shows (BR-54), and a payment that still arrives is recorded as late.
 * </ul>
 *
 * <p>Rule: BR-53; TECHNICAL_DESIGN 7.11; D-147. Reference: MoMo sample code, github.com/momo-wallet/payment; result
 * codes 0, 1000 and 1005 checked against the test environment (2026-11-08).
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MoMoGateway implements PaymentGateway {

    static final String CREATE = "/v2/gateway/api/create";
    static final String QUERY = "/v2/gateway/api/query";
    static final String CAPTURE_WALLET = "captureWallet";

    /** Paid. */
    static final int SUCCESS = 0;

    /** Opened, waiting for the Trader to confirm in the MoMo app. */
    static final int INITIATED = 1000;

    /** Authorised, not captured yet (a two-step payment; not used by captureWallet, read as awaiting). */
    static final int AUTHORIZED = 9000;

    /** The payment link expired, or MoMo has no such order. */
    static final int EXPIRED_OR_UNKNOWN = 1005;

    private static final Logger log = LoggerFactory.getLogger(MoMoGateway.class);

    private final MoMoProperties properties;
    private final MoMoApi api;
    private final JsonMapper json;
    private final Clock clock;

    @Override
    public GatewayCode code() {
        return GatewayCode.MOMO;
    }

    @Override
    public boolean configured() {
        return properties.configured();
    }

    @Override
    public Checkout checkout(CheckoutRequest request) {
        requireConfigured();
        Map<String, String> signed = new HashMap<>();
        signed.put("accessKey", properties.accessKey());
        signed.put("amount", request.amount().toBigIntegerExact().toString());
        signed.put("extraData", "");
        signed.put("ipnUrl", properties.ipnUrl());
        signed.put("orderId", request.orderCode());
        signed.put("orderInfo", request.description());
        signed.put("partnerCode", properties.partnerCode());
        signed.put("redirectUrl", request.returnUrl().toString());
        signed.put("requestId", request.orderCode());
        signed.put("requestType", CAPTURE_WALLET);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerCode", properties.partnerCode());
        body.put("requestId", request.orderCode());
        body.put("amount", request.amount().longValueExact());
        body.put("orderId", request.orderCode());
        body.put("orderInfo", request.description());
        body.put("redirectUrl", request.returnUrl().toString());
        body.put("ipnUrl", properties.ipnUrl());
        body.put("requestType", CAPTURE_WALLET);
        body.put("extraData", "");
        body.put("lang", "vi");
        body.put("signature", MoMoSignature.sign(MoMoSignature.CREATE, signed, properties.secretKey()));

        JsonNode answer = read(api.post(CREATE, body), request.orderCode());
        int result = answer.path("resultCode").asInt(-1);
        String payUrl = answer.path("payUrl").asString(null);
        if (result != SUCCESS || payUrl == null || payUrl.isBlank()) {
            throw new GatewayUnavailableException("MoMo refused order " + request.orderCode() + ": resultCode " + result
                    + " " + answer.path("message").asString(""));
        }
        // MoMo writes the ampersands of the address as HTML entities.
        return Checkout.link(URI.create(payUrl.replace("&amp;", "&")));
    }

    @Override
    public GatewayLookup lookup(String orderCode) {
        requireConfigured();
        String requestId = orderCode + "-Q" + clock.millis();
        Map<String, String> signed = Map.of(
                "accessKey",
                properties.accessKey(),
                "orderId",
                orderCode,
                "partnerCode",
                properties.partnerCode(),
                "requestId",
                requestId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerCode", properties.partnerCode());
        body.put("requestId", requestId);
        body.put("orderId", orderCode);
        body.put("lang", "vi");
        body.put("signature", MoMoSignature.sign(MoMoSignature.QUERY, signed, properties.secretKey()));

        String text = api.post(QUERY, body);
        JsonNode answer = read(text, orderCode);
        int result = answer.path("resultCode").asInt(-1);
        return switch (result) {
            case SUCCESS ->
                GatewayLookup.paid(new PaymentResult(
                        GatewayCode.MOMO,
                        orderCode,
                        true,
                        amountOf(answer, orderCode),
                        answer.path("transId").asString(null),
                        String.valueOf(result),
                        text));
            case INITIATED, AUTHORIZED -> GatewayLookup.of(GatewayLookup.State.AWAITING);
            case EXPIRED_OR_UNKNOWN -> GatewayLookup.of(GatewayLookup.State.UNKNOWN);
            case -1 -> throw new GatewayUnavailableException("MoMo answered about " + orderCode + " with no result");
            default -> GatewayLookup.of(GatewayLookup.State.CANCELLED);
        };
    }

    /** MoMo cannot cancel an unpaid order; see the class comment. */
    @Override
    public boolean cancel(String orderCode) {
        requireConfigured();
        return false;
    }

    /**
     * The payment an IPN reports, or empty for one that settles nothing (still awaiting). A wrong partner code or
     * signature is refused.
     *
     * @throws IllegalArgumentException when the body is not an authentic MoMo IPN
     */
    public Optional<PaymentResult> interpret(String body) {
        requireConfigured();
        JsonNode ipn;
        try {
            ipn = json.readTree(body == null ? "" : body);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("the MoMo IPN is not JSON", e);
        }
        Map<String, String> fields = new HashMap<>();
        for (String name : MoMoSignature.IPN) {
            JsonNode value = ipn.get(name);
            fields.put(name, value == null || value.isNull() ? "" : value.asString());
        }
        fields.put("accessKey", properties.accessKey());
        String signature = ipn.path("signature").asString("");
        String expected = MoMoSignature.sign(MoMoSignature.IPN, fields, properties.secretKey());
        if (!properties.partnerCode().equals(fields.get("partnerCode"))
                || !MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("the MoMo IPN is not signed with this deployment's secret key");
        }
        String orderCode = fields.get("orderId");
        int result = ipn.path("resultCode").asInt(-1);
        // Still awaiting, or a link that expired unpaid: no payment was attempted, so the order is not failed.
        if (result == INITIATED || result == AUTHORIZED || result == EXPIRED_OR_UNKNOWN) {
            log.info("MoMo IPN for order {} with resultCode {} settles nothing", orderCode, result);
            return Optional.empty();
        }
        if (result != SUCCESS) {
            return Optional.of(
                    new PaymentResult(GatewayCode.MOMO, orderCode, false, null, null, String.valueOf(result), body));
        }
        return Optional.of(new PaymentResult(
                GatewayCode.MOMO,
                orderCode,
                true,
                amountOf(ipn, orderCode),
                fields.get("transId"),
                String.valueOf(result),
                body));
    }

    private static BigDecimal amountOf(JsonNode node, String orderCode) {
        try {
            return new BigDecimal(node.path("amount").asString(""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("MoMo says " + orderCode + " is paid but names no amount", e);
        }
    }

    private JsonNode read(String text, String orderCode) {
        try {
            return json.readTree(text);
        } catch (JacksonException e) {
            throw new GatewayUnavailableException("MoMo answered about " + orderCode + " with no JSON", e);
        }
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new GatewayUnavailableException("MoMo is not configured on this deployment");
        }
    }
}
