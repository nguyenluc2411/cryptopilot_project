package com.cryptopilot.journal.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.journal.dto.response.JournalEntryDetailResponse;
import com.cryptopilot.journal.dto.response.JournalEntryResponse;
import com.cryptopilot.journal.model.JournalListQuery;
import com.cryptopilot.journal.model.enums.PerformanceMarket;
import com.cryptopilot.journal.model.enums.PerformanceSource;
import com.cryptopilot.journal.model.enums.TradeResult;
import com.cryptopilot.journal.service.JournalService;
import com.cryptopilot.trading.model.enums.PositionStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The journal endpoints of SCR-20 and SCR-21 (UC-22), Trader only. The caller is the owner of every record read; the service
 * never loads another Trader's.
 *
 * <p>Rule: UC-22; SRS 3.6.1; CR-04; TECHNICAL_DESIGN 8.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>. Addison-Wesley, "Remote
 * Facade" over a "Service Layer".
 */
@Tag(name = "Journal", description = "The trading journal of SCR-20 and SCR-21 (UC-22). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/journal")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class JournalController {

    private final JournalService journal;

    @Operation(
            summary = "The caller's trades, the latest entry first, 20 per page by default (SCR-20, CR-04)",
            description = "The period reads entry_time, so open trades are included; the source defaults to ALL."
                    + " The performance endpoints read exit_time and default to SIMULATED (D-140)")
    @ApiResponse(
            responseCode = "200",
            description = "One page of trades. A CLOSED trade carries its exit, realized P/L, fees, funding and net"
                    + " P/L, the amounts its plan's detail shows (BR-38)")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15: a parameter or the period is invalid")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping
    public PageResponse<JournalEntryResponse> list(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "OPEN or CLOSED; both when absent") @RequestParam(required = false)
                    PositionStatus status,
            @Parameter(description = "Only this pair") @RequestParam(required = false) UUID pairId,
            @Parameter(description = "SPOT, FUTURES or ALL; default ALL") @RequestParam(required = false)
                    PerformanceMarket market,
            @Parameter(description = "First day counted, on entry_time, UTC+7, yyyy-MM-dd; default the first trade")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(description = "First day no longer counted, on entry_time, UTC+7, yyyy-MM-dd; default tomorrow")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @Parameter(description = "SIMULATED, MANUAL or ALL; default ALL") @RequestParam(required = false)
                    PerformanceSource source,
            @Parameter(description = "Only this strategy") @RequestParam(required = false) UUID strategyId,
            @Parameter(description = "WIN, LOSS or BREAKEVEN by net P/L; only CLOSED trades match")
                    @RequestParam(required = false)
                    TradeResult result,
            @Parameter(description = "Page from 1; default 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "1 to 100; default 20") @RequestParam(required = false) Integer pageSize) {
        return journal.list(
                UUID.fromString(caller.getSubject()),
                new JournalListQuery(status, pairId, market, from, to, source, strategyId, result, page, pageSize));
    }

    @Operation(summary = "One of the caller's trades with its review (SCR-21)")
    @ApiResponse(
            responseCode = "200",
            description = "The fields of a list row, then setupDescription, lessonsLearned, mistakes and chartImageUrl")
    @ApiResponse(responseCode = "400", description = "MSG01: the id is not a UUID")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such trade among the caller's")
    @GetMapping("/{id}")
    public JournalEntryDetailResponse detail(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return journal.detail(UUID.fromString(caller.getSubject()), id);
    }
}
