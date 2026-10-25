package com.cryptopilot.trading.service.impl;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.calculator.SlippageCalculator;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The price a taker fill of the matching engine is stored at: a triggered STOP entry and a stop loss exit are
 * stop-market orders, moved against the Trader by {@code SLIPPAGE_BPS}, read for this fill, and rounded to the pair's
 * tick; nothing caps them. The tick is read only when there is slippage to round.
 *
 * <p>Rule: BR-36; D-111 (E5); D-112 (R6); TECHNICAL_DESIGN 7.8.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class TakerSlippage {

    private final SystemSettingApi settings;
    private final MarketApi market;

    /**
     * The fill price of a taker order.
     *
     * @param reference the price before slippage: the stop price, the stop loss, or the open of a gap
     * @param buy whether the order buys, so the slippage raises the price; a sell lowers it
     * @param what the order, named in the error when the pair has no tick size
     * @throws IllegalStateException when there is slippage and the pair's tick size is not known
     */
    public BigDecimal fill(BigDecimal reference, boolean buy, MarketType marketType, UUID pairId, String what) {
        int basisPoints = settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS);
        if (basisPoints == 0) {
            return reference;
        }
        BigDecimal tickSize = market.tradablePair(pairId, marketType)
                .map(pair -> pair.filters().tickSize())
                .orElseThrow(() -> new IllegalStateException("no tick size for pair " + pairId + " on " + marketType
                        + " to round the slipped fill of " + what));
        return SlippageCalculator.takerFill(reference, buy, basisPoints, tickSize);
    }
}
