package com.cryptopilot.trading.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.trading.StrategyCatalog;
import com.cryptopilot.trading.dto.response.StrategyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The strategy catalogue a Trader picks from on SCR-20, SCR-21 and SCR-23, on every plan.
 *
 * <p>Rule: SRS 3.6.1, 3.6.3; UC-22, UC-23, UC-25.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>. Addison-Wesley, "Remote
 * Facade".
 */
@Tag(name = "Strategies", description = "The strategies a Trader can choose (SCR-20, SCR-21, SCR-23). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/strategies")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class StrategyController {

    private final StrategyCatalog strategies;

    @Operation(summary = "Every strategy a Trader can choose, by name, on every plan (SCR-20, SCR-21, SCR-23)")
    @ApiResponse(responseCode = "200", description = "The strategies; the list is not paged")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping
    public List<StrategyResponse> list() {
        return strategies.all().entrySet().stream()
                .map(strategy -> new StrategyResponse(strategy.getKey(), strategy.getValue()))
                .toList();
    }
}
