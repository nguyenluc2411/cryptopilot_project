package com.cryptopilot.community.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.community.dto.request.PostRequest;
import com.cryptopilot.community.dto.response.CoinDiscussionResponse;
import com.cryptopilot.community.dto.response.PostDetailResponse;
import com.cryptopilot.community.dto.response.PostSummaryResponse;
import com.cryptopilot.community.model.FeedFilter;
import com.cryptopilot.community.model.enums.FeedTab;
import com.cryptopilot.community.model.enums.OfficialCategory;
import com.cryptopilot.community.service.PostService;
import com.cryptopilot.market.model.enums.MarketType;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The community posts of SCR-24 to SCR-26 (UC-26, UC-27, UC-29). A Guest reads the feed, the pinned posts, a post and
 * a coin's discussion (D-99); writing is for Traders. A post the caller may not see or change answers 404, whatever
 * the reason (BR-47).
 *
 * <p>Rule: UC-26, UC-27, UC-29; BR-47, BR-61; SRS 3.7.1, 3.7.2; TECHNICAL_DESIGN 8.
 */
@Tag(name = "Community", description = "Posts of SCR-24 to SCR-26 (UC-26, UC-27, UC-29). Guest reads, Trader writes.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/posts")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PostController {

    private final PostService service;

    @Operation(summary = "One page of a feed tab (SCR-24)")
    @ApiResponse(responseCode = "200", description = "The cards of the page; announcements are in /posts/pinned")
    @ApiResponse(responseCode = "400", description = "MSG01: an unknown tab or market; MSG15: page or pageSize")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping
    public PageResponse<PostSummaryResponse> feed(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "LATEST (default), MOST_LIKED (last 7 days), SAVED or MINE")
                    @RequestParam(defaultValue = "LATEST")
                    FeedTab tab,
            @Parameter(description = "Only posts tagged with this coin symbol, e.g. BTC")
                    @RequestParam(required = false)
                    String coin,
            @Parameter(description = "Only posts about this market") @RequestParam(required = false) MarketType market,
            @Parameter(description = "Only posts of this member (D-99)") @RequestParam(required = false) UUID author,
            @Parameter(description = "Search in titles and texts, 2 to 100 characters (D-99)")
                    @RequestParam(required = false)
                    String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return service.feed(callerOf(caller), tab, new FeedFilter(coin, market, author, q), page, pageSize);
    }

    @Operation(summary = "The published announcements pinned above the feed, newest first (BR-61)")
    @ApiResponse(responseCode = "200", description = "One page of official posts")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping("/pinned")
    public PageResponse<PostSummaryResponse> pinned(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "Only official posts of this category (D-99)") @RequestParam(required = false)
                    OfficialCategory category,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return service.pinned(callerOf(caller), category, page, pageSize);
    }

    @Operation(summary = "How much the community talks about a coin, for the coin page (D-99); public")
    @ApiResponse(responseCode = "200", description = "Published posts tagged with the coin: last day, last week, all")
    @ApiResponse(responseCode = "404", description = "MSG41: no such coin")
    @GetMapping("/coins/{symbol}/discussion")
    public CoinDiscussionResponse coinDiscussion(@PathVariable String symbol) {
        return service.coinDiscussion(symbol);
    }

    @Operation(summary = "A post with its full text (SCR-25)")
    @ApiResponse(responseCode = "200", description = "The post")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such post the caller may see (BR-47)")
    @GetMapping("/{id}")
    public PostDetailResponse detail(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return service.detail(callerOf(caller), id);
    }

    @Operation(summary = "Publish a post (UC-27)")
    @ApiResponse(responseCode = "201", description = "The post; the client shows MSG33")
    @ApiResponse(responseCode = "400", description = "MSG01: `errors` names each rejected field")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PostDetailResponse create(@AuthenticationPrincipal Jwt caller, @Valid @RequestBody PostRequest request) {
        return service.create(callerOf(caller), request);
    }

    @Operation(summary = "Edit one of the caller's posts (UC-29)")
    @ApiResponse(responseCode = "200", description = "The post")
    @ApiResponse(responseCode = "400", description = "MSG01: `errors` names each rejected field")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such post of the caller, or it is deleted")
    @PutMapping("/{id}")
    public PostDetailResponse update(
            @AuthenticationPrincipal Jwt caller, @PathVariable UUID id, @Valid @RequestBody PostRequest request) {
        return service.update(callerOf(caller), id, request);
    }

    @Operation(summary = "Delete one of the caller's posts (UC-29, BR-47)")
    @ApiResponse(responseCode = "204", description = "Deleted; nobody but Admin sees it any more")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such post of the caller, or it is deleted")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        service.delete(callerOf(caller), id);
    }

    /** The caller's account, or {@code null} for a Guest on a public read (D-99). */
    private static UUID callerOf(Jwt caller) {
        return caller == null ? null : UUID.fromString(caller.getSubject());
    }
}
