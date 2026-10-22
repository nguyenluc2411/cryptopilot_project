package com.cryptopilot.paper.dto.request;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.TimeInForce;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A paper Spot order to place.
 *
 * <p>The order types and fields of the exchange's Spot API:
 *
 * <ul>
 *   <li>LIMIT: {@code price} and {@code quantity}; {@code timeInForce} GTC when absent, or IOC or FOK.
 *   <li>LIMIT_MAKER: {@code price} and {@code quantity}; no {@code timeInForce}. Refused if it would execute on arrival,
 *       the post-only order (TR-12).
 *   <li>MARKET: {@code quantity}, or {@code quoteOrderQty} instead: the quote amount to spend on a BUY, to receive on a
 *       SELL.
 *   <li>STOP_LOSS and TAKE_PROFIT: {@code quantity} and {@code stopPrice} and/or {@code trailingDelta}; executed as a
 *       MARKET order once triggered. A BUY needs a {@code stopPrice}: it holds back what it would pay there.
 *   <li>STOP_LOSS_LIMIT and TAKE_PROFIT_LIMIT: {@code price}, {@code quantity} and {@code stopPrice} and/or
 *       {@code trailingDelta}; {@code timeInForce} GTC when absent, or IOC or FOK. Working as a LIMIT order once
 *       triggered.
 * </ul>
 *
 * <p>A stop's condition is met above the last price for a STOP_LOSS BUY and a TAKE_PROFIT SELL, below it for a
 * STOP_LOSS SELL and a TAKE_PROFIT BUY; a stopPrice already met is refused, as the exchange refuses an order that would
 * trigger immediately (TR-09). With a {@code trailingDelta} the stopPrice is where tracking starts: a SELL follows the
 * highest price and triggers when the price falls back by the delta, a BUY the reverse (TR-11).
 *
 * <p>The price, stopPrice and quantity are checked against the pair's Spot filters as the exchange checks them, and an
 * order that breaks one is refused, never rounded: off the tick size (BR-30) or the step size (BR-23), out of the
 * price or quantity bounds, worth less or more than the notional bounds, or too far from the last price
 * ({@code PAPER_PRICE_FILTER}, {@code PAPER_LOT_SIZE}, {@code PAPER_MARKET_LOT_SIZE}, {@code PAPER_NOTIONAL},
 * {@code PAPER_PERCENT_PRICE}, {@code PAPER_TRAILING_DELTA}).
 *
 * @param pairId the pair, enabled on the market
 * @param market {@code SPOT}
 * @param side BUY or SELL
 * @param type MARKET, LIMIT, LIMIT_MAKER, STOP_LOSS, STOP_LOSS_LIMIT, TAKE_PROFIT or TAKE_PROFIT_LIMIT
 * @param timeInForce GTC, IOC or FOK, for LIMIT and the limit stops; absent for the others
 * @param price LIMIT only: the price, more than zero
 * @param quantity the base quantity, more than zero
 * @param quoteOrderQty MARKET only, instead of {@code quantity}: what to spend (BUY) or receive (SELL), in the quote
 *     asset
 * @param stopPrice the stops only: the price that triggers it, or with a trailing delta where tracking starts
 * @param trailingDelta the stops only: how far back from the best price it triggers, in basis points (100 = 1 %),
 *     within the pair's {@code TRAILING_DELTA} range for the side the stop triggers on
 * @param clientOrderId the idempotency key: a request sent again with the same key, e.g. after a timeout, returns the
 *     order already placed instead of placing a second one; up to 64 letters, digits and {@code . _ : -}. Optional
 */
public record PlaceOrderRequest(
        @NotNull(message = "MSG01") UUID pairId,

        @NotNull(message = "MSG01") MarketType market,

        @NotNull(message = "MSG01") OrderSide side,

        @NotNull(message = "MSG01") OrderType type,

        TimeInForce timeInForce,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal price,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal quantity,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 20, fraction = 8, message = "MSG15")
        BigDecimal quoteOrderQty,

        @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}", message = "MSG15")
        String clientOrderId,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal stopPrice,

        @Positive(message = "MSG15") Long trailingDelta) {}
