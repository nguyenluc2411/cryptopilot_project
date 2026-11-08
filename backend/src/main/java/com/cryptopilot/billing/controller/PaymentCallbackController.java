package com.cryptopilot.billing.controller;

import com.cryptopilot.billing.service.PaymentCallbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The callbacks of the payment gateways. They come from the gateway's servers, not from a signed-in user, so they are
 * open to anyone and authenticated by the gateway's own credential instead (BR-53).
 *
 * <p>Rule: BR-53; NSF-12; D-146.
 */
@Tag(name = "Payment callbacks", description = "IPN of the payment gateways; authenticated by the gateway (BR-53).")
@RestController
@RequestMapping("/api/v1/billing/ipn")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaymentCallbackController {

    private final PaymentCallbackService callbacks;

    @Operation(summary = "SePay IPN: a payment of an order (NSF-12)")
    @ApiResponse(responseCode = "200", description = "Received; SePay stops sending it")
    @ApiResponse(responseCode = "400", description = "Not a SePay notification")
    @ApiResponse(responseCode = "401", description = "X-Secret-Key is not this deployment's IPN secret key")
    @PostMapping(path = "/sepay", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Boolean>> sepay(
            @RequestHeader(name = "X-Secret-Key", required = false) String secretKey, @RequestBody String body) {
        try {
            if (!callbacks.acceptSePay(secretKey, body)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("success", false));
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }
}
