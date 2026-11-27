package com.cryptopilot.market.dto.request;

import com.cryptopilot.market.model.enums.MarketType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * An administrator cuts a market's exchange streams on a test environment (RF-12), to see the engines lose and regain
 * their data (NFR-06, DS-06b).
 *
 * @param market the market whose streams are cut
 * @param outageSeconds how long the streams stay down before they reconnect by themselves, 0 to 300: past
 *     {@code reconnecting-after} (10 s) the market is named reconnecting, past {@code pause-after} (30 s) it is paused
 *     and the OKX reference prices are shown (D-159)
 */
public record StreamFaultRequest(
        @NotNull(message = "MSG01") MarketType market,

        @NotNull(message = "MSG01")
        @Min(value = 0, message = "MSG01")
        @Max(value = StreamFaultRequest.MAX_OUTAGE_SECONDS, message = "MSG01")
        Integer outageSeconds) {

    /** The longest outage the switch accepts: five minutes. */
    public static final long MAX_OUTAGE_SECONDS = 300;
}
