package com.cryptopilot.paper.dto.request;

import com.cryptopilot.paper.model.enums.OrderType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Closes a position, or every position, as the trading screen's Close and Close All buttons do; the exchange has no
 * endpoint of its own for it. Each close is a reduce-only order placed as {@code POST /futures/order} places it.
 *
 * <ul>
 *   <li>MARKET: {@code symbol} for one position, absent for every position.
 *   <li>LIMIT: {@code symbol} and {@code price}; it waits in the book (GTC) for what it does not take at once.
 * </ul>
 *
 * @param symbol the pair; every open position when absent (MARKET only)
 * @param type MARKET or LIMIT
 * @param price LIMIT only, on the pair's tick
 * @param percent the share of the position closed, 1 to 100; 100 when absent. A share of less than the whole is
 *     rounded down to the pair's step
 */
public record FuturesClosePositionRequest(
        @Size(max = 32, message = "MSG15") String symbol,

        @NotNull(message = "MSG01") OrderType type,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal price,

        @DecimalMin(value = "1", message = "MSG15")
        @DecimalMax(value = "100", message = "MSG15")
        @Digits(integer = 3, fraction = 2, message = "MSG15")
        BigDecimal percent) {}
