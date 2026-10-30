package com.cryptopilot.paper.service.impl;

import com.cryptopilot.paper.model.enums.Liquidity;
import com.cryptopilot.paper.service.FuturesCommissionPolicy;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * No commission on paper Futures, until the admin configures it (plan 2.3). The system settings {@code FUTURES_MAKER_FEE}
 * and {@code FUTURES_TAKER_FEE} are the trading plan's and are not read here.
 *
 * <p>Rule: TR-44; Q-F2.
 */
@Service
class ZeroFuturesCommissionPolicy implements FuturesCommissionPolicy {

    @Override
    public BigDecimal rate(UUID userId, UUID pairId, Liquidity liquidity) {
        return BigDecimal.ZERO;
    }
}
