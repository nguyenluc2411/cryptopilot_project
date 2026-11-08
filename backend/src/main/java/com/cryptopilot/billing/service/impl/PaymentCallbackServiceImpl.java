package com.cryptopilot.billing.service.impl;

import com.cryptopilot.billing.client.MoMoGateway;
import com.cryptopilot.billing.client.SePayGateway;
import com.cryptopilot.billing.service.PaymentCallbackService;
import com.cryptopilot.billing.service.PaymentSettlementService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Authenticates a gateway callback and hands the payment it reports to the settlement (BR-53). A callback is accepted
 * whether or not the gateway is switched off: the switch only stops new orders (D-146).
 *
 * <p>Rule: BR-53; NSF-12; D-146.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaymentCallbackServiceImpl implements PaymentCallbackService {

    private static final Logger log = LoggerFactory.getLogger(PaymentCallbackServiceImpl.class);

    private final SePayGateway sepay;
    private final MoMoGateway momo;
    private final PaymentSettlementService settlement;

    @Override
    public boolean acceptSePay(String secretKey, String body) {
        if (!sepay.authentic(secretKey)) {
            log.warn("refused a SePay IPN without this deployment's IPN secret key");
            return false;
        }
        sepay.interpret(body).ifPresent(settlement::settle);
        return true;
    }

    @Override
    public void acceptMoMo(String body) {
        momo.interpret(body).ifPresent(settlement::settle);
    }
}
