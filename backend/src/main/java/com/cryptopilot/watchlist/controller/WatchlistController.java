package com.cryptopilot.watchlist.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.watchlist.dto.request.AddWatchlistItemRequest;
import com.cryptopilot.watchlist.dto.request.UpdateWatchlistItemRequest;
import com.cryptopilot.watchlist.dto.response.WatchlistItemResponse;
import com.cryptopilot.watchlist.dto.response.WatchlistResponse;
import com.cryptopilot.watchlist.service.WatchlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The watchlist endpoints of SCR-12 (UC-12), Trader only. The caller is always the owner: the service never loads
 * another Trader's row.
 *
 * <p>Rule: UC-12; BR-15, BR-16; SRS 3.4.1; TECHNICAL_DESIGN 8.
 *
 * <p>Reference: Fielding, R. T. (2000). <i>Architectural Styles and the Design of Network-based Software
 * Architectures</i>. Doctoral dissertation, University of California, Irvine, ch. 5 (resources). IETF RFC 9110 (2022).
 * <i>HTTP Semantics</i>, §9.3 (methods) and §15 (status codes).
 */
@Tag(name = "Watchlist", description = "The watchlist of SCR-12 (UC-12). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/watchlist")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class WatchlistController {

    private final WatchlistService watchlist;

    @Operation(summary = "The caller's watchlist in list order, with the plan's limit (SCR-12)")
    @ApiResponse(responseCode = "200", description = "Every row; `limit` is null when the plan has none")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping
    public WatchlistResponse list(@AuthenticationPrincipal Jwt caller) {
        return watchlist.list(callerOf(caller));
    }

    @Operation(summary = "Add a pair at the end of the list (UC-12, BR-15)")
    @ApiResponse(responseCode = "201", description = "The new row; the client shows MSG25")
    @ApiResponse(responseCode = "400", description = "MSG01: `errors` names each rejected field")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the pair is unknown or not listed on any market")
    @ApiResponse(responseCode = "409", description = "MSG45: the pair is already watched; MSG27: the plan's limit")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WatchlistItemResponse add(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody AddWatchlistItemRequest request) {
        return watchlist.add(callerOf(caller), request);
    }

    @Operation(summary = "Change the label, note or position of a row (SRS 3.4.1)")
    @ApiResponse(responseCode = "200", description = "The row")
    @ApiResponse(responseCode = "400", description = "MSG01: `errors` names each rejected field")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such row of the caller")
    @PatchMapping("/{id}")
    public WatchlistItemResponse update(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateWatchlistItemRequest request) {
        return watchlist.update(callerOf(caller), id, request);
    }

    @Operation(summary = "Remove a row and its alerts (UC-12, BR-16)")
    @ApiResponse(responseCode = "204", description = "Removed")
    @ApiResponse(
            responseCode = "400",
            description =
                    "MSG26: the pair has alerts and `confirm` is not true, or `expectedAlertCount` is no longer the"
                            + " number of alerts; nothing was removed")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such row of the caller")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID id,
            @Parameter(description = "true once the Trader confirmed MSG26; default false")
                    @RequestParam(defaultValue = "false")
                    boolean confirm,
            @Parameter(
                            description = "The {n} of the MSG26 the Trader confirmed; when the row now holds another"
                                    + " number, MSG26 answers again with it. Optional")
                    @RequestParam(required = false)
                    Long expectedAlertCount) {
        watchlist.remove(callerOf(caller), id, confirm, expectedAlertCount);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}
