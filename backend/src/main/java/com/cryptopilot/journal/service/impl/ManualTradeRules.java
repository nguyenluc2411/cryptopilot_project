package com.cryptopilot.journal.service.impl;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.journal.dto.request.ManualTradeRequest;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The rules a manual trade is checked against beyond its single fields, each reported under the field it concerns, and
 * its realized P/L.
 *
 * <ul>
 *   <li>Spot is LONG only and has neither leverage nor funding; Futures needs a leverage from 1 to 125.
 *   <li>The pair is a stored pair listed on the market.
 *   <li>The entry is not in the future; an exit has both its price and its time, not before the entry nor in the
 *       future.
 *   <li>With both a stop loss and a take profit, the price order of BR-22: LONG stop loss &lt; entry &lt; take profit,
 *       SHORT the reverse (MSG16).
 * </ul>
 *
 * <p>Rule: BR-21, BR-22, BR-35, BR-41; SRS 3.6.2; MSG01, MSG15, MSG16.
 */
final class ManualTradeRules {

    static final int MAX_LEVERAGE = 125;

    private static final String REQUIRED = "MSG01";
    private static final String OUT_OF_RANGE = "MSG15";
    private static final String WRONG_SIDE = "MSG16";

    private ManualTradeRules() {}

    /** Every broken rule, field → message code, in the order above; empty when the trade can be stored. */
    static Map<String, String> violations(ManualTradeRequest trade, Optional<PairListing> pair, Instant now) {
        Map<String, String> errors = new LinkedHashMap<>();
        boolean spot = trade.market() == MarketType.SPOT;
        if (spot && trade.direction() == Direction.SHORT) {
            errors.put("direction", WRONG_SIDE);
        }
        if (spot && trade.leverage() != null) {
            errors.put("leverage", OUT_OF_RANGE);
        } else if (!spot && trade.leverage() == null) {
            errors.put("leverage", REQUIRED);
        } else if (!spot && (trade.leverage() < 1 || trade.leverage() > MAX_LEVERAGE)) {
            errors.put("leverage", OUT_OF_RANGE);
        }
        if (spot && trade.fundingFeeAmount() != null) {
            errors.put("fundingFeeAmount", OUT_OF_RANGE);
        }
        if (pair.isEmpty() || !(spot ? pair.get().spotListed() : pair.get().futuresListed())) {
            errors.put("pairId", OUT_OF_RANGE);
        }
        if (trade.entryTime().isAfter(now)) {
            errors.put("entryTime", OUT_OF_RANGE);
        }
        if (trade.exitPrice() == null && trade.exitTime() != null) {
            errors.put("exitPrice", REQUIRED);
        } else if (trade.exitPrice() != null && trade.exitTime() == null) {
            errors.put("exitTime", REQUIRED);
        } else if (trade.exitTime() != null
                && (trade.exitTime().isBefore(trade.entryTime())
                        || trade.exitTime().isAfter(now))) {
            errors.put("exitTime", OUT_OF_RANGE);
        }
        if (trade.stopLoss() != null && trade.takeProfit() != null) {
            int side = trade.direction() == Direction.LONG ? 1 : -1;
            if (trade.stopLoss().compareTo(trade.entryPrice()) * side >= 0) {
                errors.put("stopLoss", WRONG_SIDE);
            }
            if (trade.takeProfit().compareTo(trade.entryPrice()) * side <= 0) {
                errors.put("takeProfit", WRONG_SIDE);
            }
        }
        return errors;
    }

    /**
     * The price result of a closed trade, BR-35: LONG (exit − entry) × quantity, SHORT (entry − exit) × quantity, at
     * the stored amount scale; {@code null} while it is open.
     */
    static BigDecimal realizedPnl(ManualTradeRequest trade) {
        if (trade.exitPrice() == null) {
            return null;
        }
        BigDecimal move = trade.exitPrice().subtract(trade.entryPrice());
        BigDecimal signed = trade.direction() == Direction.LONG ? move : move.negate();
        return Rounding.toAmount(signed.multiply(trade.quantity()));
    }
}
