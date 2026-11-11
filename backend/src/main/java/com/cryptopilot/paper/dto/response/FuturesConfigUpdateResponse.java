package com.cryptopilot.paper.dto.response;

import com.cryptopilot.paper.model.enums.MarginType;
import java.time.Instant;

/**
 * A change of the Trader's paper Futures settings, pushed on {@code /user/queue/futures.config} (TR-43). Binance: the
 * {@code ACCOUNT_CONFIG_UPDATE} event of the user data stream, {@code ac} for a pair's leverage and {@code ai} for the
 * position mode; here a pair's margin type is pushed the same way.
 *
 * @param symbol the pair changed, or {@code null} for the position mode
 * @param leverage the pair's leverage afterwards, or {@code null}
 * @param marginType the pair's margin type afterwards, or {@code null}
 * @param dualSidePosition the position mode afterwards, or {@code null} for a pair's change
 * @param eventTime when the change was made
 */
public record FuturesConfigUpdateResponse(
        String symbol, Integer leverage, MarginType marginType, Boolean dualSidePosition, Instant eventTime) {}
