package com.cryptopilot.journal.dto.request;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A trade the Trader made outside CryptoPilot, as SCR-22 records it; an edit replaces every field. The exit price and
 * time come together or not at all: without them the trade is OPEN. The review texts are those of SCR-21; the chart
 * image is saved with the review.
 *
 * <p>Rule: BR-22, BR-35, BR-38, BR-41; UC-24; SRS 3.6.2; D-97, D-139.
 *
 * @param pairId the pair traded
 * @param market SPOT or FUTURES
 * @param direction LONG or SHORT; Spot is LONG only
 * @param leverage Futures only, 1 to 125
 * @param entryPrice the entry price, greater than 0
 * @param quantity the quantity, greater than 0
 * @param entryTime when the entry filled, not in the future
 * @param stopLoss the stop loss, optional, greater than 0
 * @param takeProfit the take profit, optional, greater than 0
 * @param exitPrice the exit price, greater than 0; with {@code exitTime} or not at all
 * @param exitTime when the exit filled, not before the entry nor in the future; with {@code exitPrice} or not at all
 * @param feeAmount the fees paid, 0 or more; 0 when left out
 * @param fundingFeeAmount Futures only: the funding, positive when received, negative when paid
 * @param strategyId the strategy followed, or {@code null}
 * @param setupDescription the setup, up to 4,000 characters
 * @param lessonsLearned the lessons, up to 4,000 characters
 * @param mistakes the mistakes, up to 4,000 characters
 */
public record ManualTradeRequest(
        @NotNull(message = "MSG01") UUID pairId,
        @NotNull(message = "MSG01") MarketType market,
        @NotNull(message = "MSG01") Direction direction,
        Integer leverage,

        @NotNull(message = "MSG01") @Positive(message = "MSG15") @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal entryPrice,

        @NotNull(message = "MSG01") @Positive(message = "MSG15") @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal quantity,

        @NotNull(message = "MSG01") Instant entryTime,

        @Positive(message = "MSG15") @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal stopLoss,

        @Positive(message = "MSG15") @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal takeProfit,

        @Positive(message = "MSG15") @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal exitPrice,

        Instant exitTime,

        @PositiveOrZero(message = "MSG15") @Digits(integer = 20, fraction = 8, message = "MSG15")
        BigDecimal feeAmount,

        @Digits(integer = 20, fraction = 8, message = "MSG15")
        BigDecimal fundingFeeAmount,

        UUID strategyId,
        @Size(max = 4000, message = "MSG15") String setupDescription,
        @Size(max = 4000, message = "MSG15") String lessonsLearned,
        @Size(max = 4000, message = "MSG15") String mistakes) {}
