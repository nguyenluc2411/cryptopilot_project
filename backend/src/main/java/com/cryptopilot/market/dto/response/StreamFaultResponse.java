package com.cryptopilot.market.dto.response;

import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;

/**
 * What the fault switch cut (RF-12).
 *
 * @param market the market whose streams were cut
 * @param connectionsCut how many open connections were cut; 0 when the streams are off or none was open
 * @param cutAt when they were cut, the start of the reconnect time NFR-06 measures
 * @param reconnectFrom when the first reconnection attempt starts
 */
public record StreamFaultResponse(MarketType market, int connectionsCut, Instant cutAt, Instant reconnectFrom) {}
