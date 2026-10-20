package com.cryptopilot.trading.service.impl;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.FeeSchedule;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The simulated fee rates of a market, read from the system settings on each call so that an administrator's change
 * applies to the next close.
 *
 * <p>Rule: BR-36; D-97; TECHNICAL_DESIGN 7.8.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FeeRates {

    static final String SPOT_MAKER = "SPOT_MAKER_FEE";
    static final String SPOT_TAKER = "SPOT_TAKER_FEE";
    static final String FUTURES_MAKER = "FUTURES_MAKER_FEE";
    static final String FUTURES_TAKER = "FUTURES_TAKER_FEE";
    static final String LIQUIDATION_CLEARANCE = "LIQUIDATION_CLEARANCE_FEE";

    private final SystemSettingApi settings;

    /** The rates of the market; Spot has no clearance fee. */
    public FeeSchedule of(MarketType market) {
        if (market == MarketType.SPOT) {
            return new FeeSchedule(settings.decimal(SPOT_MAKER), settings.decimal(SPOT_TAKER), null);
        }
        return new FeeSchedule(
                settings.decimal(FUTURES_MAKER),
                settings.decimal(FUTURES_TAKER),
                settings.decimal(LIQUIDATION_CLEARANCE));
    }
}
