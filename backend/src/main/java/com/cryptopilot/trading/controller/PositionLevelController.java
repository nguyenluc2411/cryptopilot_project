package com.cryptopilot.trading.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.trading.dto.request.ChangePositionLevelsRequest;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import com.cryptopilot.trading.service.PositionLevelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The change of the stop loss and take profit of an open position (SCR-18, D-124), Trader only, addressed by the plan
 * that opened it. The caller is the plan's owner; the service never loads another Trader's plan.
 *
 * <p>Rule: UC-21; BR-22, BR-28, BR-29; D-124; TECHNICAL_DESIGN 8.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>. Addison-Wesley, "Remote
 * Facade" over a "Service Layer".
 */
@Tag(name = "Trading plans", description = "Trading plans of SCR-16 to SCR-18 (UC-16 to UC-20). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PositionLevelController {

    private final PositionLevelService levels;

    @Operation(summary = "Change the stop loss and/or take profit of the plan's open position (SCR-18, D-124)")
    @ApiResponse(
            responseCode = "200",
            description = "The plan with its updated position: current and original levels, the history of the"
                    + " changes, stopWidened, the breakeven stop, and levelWarnings when the stop was moved away from"
                    + " the entry beyond the risk profile's limits (non-blocking). The new levels act from the next"
                    + " 1-minute candle")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01 on `stopLoss`: neither level given; MSG15: a level not above 0 or with too many"
                    + " digits; MSG16 on the field: a level on the wrong side of, or already reached by, the current"
                    + " last price (LONG stopLoss < last < takeProfit, SHORT the mirror), or a Futures stop loss at or"
                    + " beyond the liquidation price (BR-28)")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such plan of the caller")
    @ApiResponse(
            responseCode = "409",
            description = "MSG43: the plan has no OPEN position, or it closed while the change was made; nothing"
                    + " changed")
    @ApiResponse(responseCode = "503", description = "MSG43: no current last price to check the new levels against")
    @PatchMapping("/{id}/position-levels")
    public TradingPlanResponse changeLevels(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID id,
            @Valid @RequestBody ChangePositionLevelsRequest request) {
        return levels.changeLevels(UUID.fromString(caller.getSubject()), id, request);
    }
}
