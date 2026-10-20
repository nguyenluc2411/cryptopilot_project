package com.cryptopilot.community.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.community.dto.response.AuthorResponse;
import com.cryptopilot.community.dto.response.EngagementResponse;
import com.cryptopilot.community.dto.response.MemberProfileResponse;
import com.cryptopilot.community.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Member pages and following, as on CoinMarketCap Community (D-99). Anyone reads a page and its follower lists; a
 * Trader follows. A member's posts are {@code GET /posts?author=}.
 *
 * <p>Rule: D-99.
 */
@Tag(name = "Members", description = "Public member pages and following (D-99). Guest reads, Trader follows.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/community/members/{memberId}")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MemberController {

    private final MemberService service;

    @Operation(summary = "A member's public page; public")
    @ApiResponse(responseCode = "200", description = "Name, avatar, join date, posts, likes received and follows")
    @ApiResponse(responseCode = "404", description = "MSG41: no such member, or banned")
    @GetMapping
    public MemberProfileResponse profile(@AuthenticationPrincipal Jwt caller, @PathVariable UUID memberId) {
        return service.profile(caller == null ? null : UUID.fromString(caller.getSubject()), memberId);
    }

    @Operation(summary = "Follow a member")
    @ApiResponse(responseCode = "200", description = "Following, with the member's follower count")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such member, or banned")
    @ApiResponse(responseCode = "409", description = "MSG43: the caller's own account (`FOLLOW_SELF`)")
    @PostMapping("/follow")
    public EngagementResponse follow(@AuthenticationPrincipal Jwt caller, @PathVariable UUID memberId) {
        return service.follow(UUID.fromString(caller.getSubject()), memberId, true);
    }

    @Operation(summary = "Stop following a member")
    @ApiResponse(responseCode = "200", description = "Not following, with the member's follower count")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @DeleteMapping("/follow")
    public EngagementResponse unfollow(@AuthenticationPrincipal Jwt caller, @PathVariable UUID memberId) {
        return service.follow(UUID.fromString(caller.getSubject()), memberId, false);
    }

    @Operation(summary = "A member's followers, the most recent first; public")
    @ApiResponse(responseCode = "200", description = "One page of members")
    @ApiResponse(responseCode = "404", description = "MSG41: no such member, or banned")
    @GetMapping("/followers")
    public PageResponse<AuthorResponse> followers(
            @PathVariable UUID memberId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return service.followers(memberId, page, pageSize);
    }

    @Operation(summary = "The members a member follows, the most recent first; public")
    @ApiResponse(responseCode = "200", description = "One page of members")
    @ApiResponse(responseCode = "404", description = "MSG41: no such member, or banned")
    @GetMapping("/following")
    public PageResponse<AuthorResponse> following(
            @PathVariable UUID memberId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return service.following(memberId, page, pageSize);
    }
}
