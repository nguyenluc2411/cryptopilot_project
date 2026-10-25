package com.cryptopilot.journal.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.journal.dto.response.JournalEntryResponse;
import com.cryptopilot.journal.model.JournalListQuery;
import com.cryptopilot.journal.service.JournalService;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.PositionStatus;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The journal endpoints of SCR-20 (UC-22), Trader only. The caller is the owner of every record read; the service
 * never loads another Trader's.
 *
 * <p>Rule: UC-22; SRS 3.6.1; CR-04; TECHNICAL_DESIGN 8.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>. Addison-Wesley, "Remote
 * Facade" over a "Service Layer".
 */
@Tag(name = "Journal", description = "The trading journal of SCR-20 (UC-22). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/journal")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class JournalController {

    private final JournalService journal;

    @Operation(summary = "The caller's trades, the latest entry first, 20 per page by default (SCR-20, CR-04)")
    @ApiResponse(
            responseCode = "200",
            description = "One page of trades. A CLOSED trade carries its exit, realized P/L, fees, funding and net"
                    + " P/L, the amounts its plan's detail shows (BR-38)")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15: a parameter is invalid")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping
    public PageResponse<JournalEntryResponse> list(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "OPEN or CLOSED; both when absent") @RequestParam(required = false)
                    PositionStatus status,
            @Parameter(description = "Only this pair") @RequestParam(required = false) UUID pairId,
            @Parameter(description = "SPOT or FUTURES") @RequestParam(required = false) MarketType market,
            @Parameter(description = "Page from 1; default 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "1 to 100; default 20") @RequestParam(required = false) Integer pageSize) {
        return journal.list(
                UUID.fromString(caller.getSubject()), new JournalListQuery(status, pairId, market, page, pageSize));
    }
}
