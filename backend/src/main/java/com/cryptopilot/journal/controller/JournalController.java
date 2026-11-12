package com.cryptopilot.journal.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.journal.dto.request.JournalReviewRequest;
import com.cryptopilot.journal.dto.request.ManualTradeRequest;
import com.cryptopilot.journal.dto.response.ChartImageUploadResponse;
import com.cryptopilot.journal.dto.response.JournalEntryDetailResponse;
import com.cryptopilot.journal.dto.response.JournalEntryResponse;
import com.cryptopilot.journal.model.JournalListQuery;
import com.cryptopilot.journal.model.enums.PerformanceMarket;
import com.cryptopilot.journal.model.enums.PerformanceSource;
import com.cryptopilot.journal.model.enums.TradeResult;
import com.cryptopilot.journal.service.JournalReviewService;
import com.cryptopilot.journal.service.JournalService;
import com.cryptopilot.journal.service.ManualTradeService;
import com.cryptopilot.trading.model.enums.PositionStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The journal endpoints of SCR-20, SCR-21 and SCR-22 (UC-22, UC-23, UC-24), Trader only. The caller is the owner of every record read; the
 * service never loads another Trader's.
 *
 * <p>Rule: UC-22, UC-23, UC-24; BR-40, BR-41; SRS 3.6.1, 3.6.2; CR-04; TECHNICAL_DESIGN 8.
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
    private final JournalReviewService reviews;
    private final ManualTradeService manualTrades;

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

    @Operation(
            summary = "Records a trade made outside CryptoPilot (SCR-22, UC-24)",
            description = "Source MANUAL. With exitPrice and exitTime the trade is CLOSED by MANUAL, its realized P/L"
                    + " computed from the prices (BR-35) and its fee 0 when left out; without them it is OPEN. The"
                    + " performance endpoints read SIMULATED trades unless source says otherwise (BR-41)")
    @ApiResponse(responseCode = "201", description = "The trade as GET /journal/{id} shows it")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01: a required field is missing; MSG15: a value out of range, an entry or exit in the"
                    + " future, an exit before the entry, an unknown pair or strategy; MSG16: a Spot SHORT, or a"
                    + " stop loss or take profit on the wrong side of the entry (BR-22)")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JournalEntryDetailResponse record(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody ManualTradeRequest request) {
        return manualTrades.record(UUID.fromString(caller.getSubject()), request);
    }

    @Operation(
            summary = "Replaces every field of one of the caller's manual trades (SCR-22, UC-24)",
            description = "The chart image is kept; it is changed with the review. A SIMULATED trade's execution facts"
                    + " cannot be edited (BR-40)")
    @ApiResponse(responseCode = "200", description = "The trade as GET /journal/{id} shows it")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15, MSG16: as for recording a manual trade")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such trade among the caller's")
    @ApiResponse(responseCode = "409", description = "MSG43: the trade is SIMULATED")
    @PutMapping("/{id}")
    public JournalEntryDetailResponse edit(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID id,
            @Valid @RequestBody ManualTradeRequest request) {
        return manualTrades.edit(UUID.fromString(caller.getSubject()), id, request);
    }

    @Operation(summary = "Deletes one of the caller's manual trades permanently, with its chart image (UC-24)")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such trade among the caller's")
    @ApiResponse(responseCode = "409", description = "MSG43: the trade is SIMULATED (BR-40)")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        manualTrades.delete(UUID.fromString(caller.getSubject()), id);
    }

    @Operation(
            summary = "Saves the review of one of the caller's trades (SCR-21, UC-23)",
            description = "Replaces strategy, setup description, lessons learned, mistakes and the chart image; a"
                    + " field left out is cleared. The execution facts are never changed here (BR-40): a body naming"
                    + " one, or any other field, is refused. The client shows MSG14")
    @ApiResponse(responseCode = "200", description = "The trade as GET /journal/{id} shows it, with its new review")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01: a field that is not part of the review; MSG15: a text over 4,000 characters or an"
                    + " unknown strategy; MSG32: a chart image that is not a new upload of the caller, or not JPG,"
                    + " PNG or WEBP within 5 MB")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such trade among the caller's")
    @ApiResponse(responseCode = "503", description = "MSG43: the media storage cannot check the image")
    @PutMapping("/{id}/review")
    public JournalEntryDetailResponse review(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID id,
            @Valid @RequestBody JournalReviewRequest request) {
        return reviews.review(UUID.fromString(caller.getSubject()), id, request);
    }

    @Operation(summary = "A signed form to upload the chart image of one of the caller's trades (SCR-21)")
    @ApiResponse(responseCode = "200", description = "The form fields, the accepted formats and the size limit")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such trade among the caller's")
    @ApiResponse(responseCode = "503", description = "MSG43: the media storage is not available")
    @PostMapping("/{id}/chart-image/upload-signature")
    public ChartImageUploadResponse signChartImage(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return reviews.signChartImage(UUID.fromString(caller.getSubject()), id);
    }
}
