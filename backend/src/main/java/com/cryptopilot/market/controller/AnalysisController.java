package com.cryptopilot.market.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.market.dto.response.AnalysisResponse;
import com.cryptopilot.market.service.AnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The computed analysis of SCR-10 and SCR-11 (UC-10, UC-11): indicators, support and resistance, component scores and
 * the setup score of the caller's preset. Trader only — the filter chain keeps {@code /api/v1/analysis/**} out of the
 * public market data (SRS 3.1.3).
 *
 * <p>Times are ISO 8601 in UTC and decimals are strings (TECHNICAL_DESIGN 8). A value that cannot be computed yet is
 * {@code null}.
 *
 * <p>Rule: UC-10, UC-11, BR-07, BR-08, BR-12, BR-13, BR-14; SRS 3.1.3, 3.3.2; TECHNICAL_DESIGN 7.4 and 8; D-53.
 */
@Tag(
        name = "Analysis",
        description = "Indicators, support/resistance and setup score of a pair (UC-10, UC-11). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/analysis")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AnalysisController {

    private final AnalysisService analysis;

    /** The latest analysis of a series and the setup score of the caller's preset (UC-10, UC-11). */
    @Operation(summary = "Latest analysis of a pair and the setup score of the caller's preset (UC-10, UC-11)")
    @ApiResponse(responseCode = "200", description = "The analysis; values not computable yet are null")
    @ApiResponse(responseCode = "400", description = "MSG01: a parameter or the body is invalid")
    @ApiResponse(
            responseCode = "404",
            description = "MSG41: no pair with that symbol is enabled on that market (BR-07)")
    @GetMapping("/{market}/{symbol}")
    public AnalysisResponse analysis(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "spot or futures, in any case") @PathVariable String market,
            @PathVariable String symbol,
            @Parameter(description = "15m, 1h, 4h or 1d (BR-08); default 1h (SRS 3.3.2)")
                    @RequestParam(required = false)
                    String tf) {
        return analysis.analysis(market, symbol, tf, UUID.fromString(caller.getSubject()));
    }
}
