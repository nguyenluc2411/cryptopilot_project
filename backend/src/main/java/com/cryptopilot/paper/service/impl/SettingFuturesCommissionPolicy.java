package com.cryptopilot.paper.service.impl;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.paper.model.enums.Liquidity;
import com.cryptopilot.paper.service.FuturesCommissionPolicy;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The commission of paper Futures from the system settings {@code FUTURES_MAKER_FEE} and {@code FUTURES_TAKER_FEE}
 * (seeded at Binance's standard 0.02 % and 0.05 %), read on each fill so that an administrator's change applies to the
 * next one. They are the rates the trading plan estimates with, so a plan carried out on the demo account pays what
 * its panel showed (RF-33, D-117: one engine).
 *
 * <p>Rule: TR-44; D-127; Q-F2; RF-33.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class SettingFuturesCommissionPolicy implements FuturesCommissionPolicy {

    static final String MAKER = "FUTURES_MAKER_FEE";
    static final String TAKER = "FUTURES_TAKER_FEE";

    private final SystemSettingApi settings;

    @Override
    public BigDecimal rate(UUID userId, UUID pairId, Liquidity liquidity) {
        return settings.decimal(liquidity == Liquidity.MAKER ? MAKER : TAKER);
    }
}
