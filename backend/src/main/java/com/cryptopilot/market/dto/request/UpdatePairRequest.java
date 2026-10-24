package com.cryptopilot.market.dto.request;

import com.cryptopilot.market.model.enums.PairStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Everything an administrator sets on a pair in SCR-37, replaced as a whole (UC-45). A market is offered only while
 * the pair is ACTIVE and the market's switch is on (BR-07); taking one out of service follows BR-59.
 *
 * <p>Rule: UC-45; BR-07, BR-26, BR-59.
 *
 * @param status ACTIVE or INACTIVE, the switch of the pair as a whole
 * @param spotEnabled whether the Spot market is on
 * @param futuresEnabled whether the futures market is on
 * @param maxLeverage the highest leverage of the futures market, 1 to 125; required when the futures market is on and
 *     ignored otherwise
 * @param displayOrder the place in lists, from 1, lowest first
 */
public record UpdatePairRequest(
        @NotNull(message = "MSG01") PairStatus status,
        boolean spotEnabled,
        boolean futuresEnabled,

        @Min(value = 1, message = "MSG15") @Max(value = 125, message = "MSG15")
        Integer maxLeverage,

        @NotNull(message = "MSG01") @Min(value = 1, message = "MSG15")
        Integer displayOrder) {}
