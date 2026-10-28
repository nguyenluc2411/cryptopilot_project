package com.cryptopilot.community.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.community.dto.response.AuthorResponse;
import com.cryptopilot.community.dto.response.EngagementResponse;
import com.cryptopilot.community.dto.response.InfluencerResponse;
import com.cryptopilot.community.dto.response.MemberProfileResponse;
import com.cryptopilot.community.model.enums.InfluencerRank;
import com.cryptopilot.community.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Member pages, following and blocking, as on CoinMarketCap Community (D-125). Anyone reads a page and its follower
 * lists; a Trader follows, blocks and looks members up to mention them. A member's posts are
 * {@code GET /posts?author=}.
 *
 * <p>Rule: D-99.
 */
@Tag(
        name = "Members",
        description =
                "Public member pages, following and blocking (D-99, D-125). Guest reads, Trader follows and blocks.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MemberController {

    private static final String MEMBER = "/api/v1/community/members/{memberId}";

    private final MemberService service;

    @Operation(summary = "A member's public page; public")
    @ApiResponse(responseCode = "200", description = "Name, avatar, join date, posts, likes received and follows")
    @ApiResponse(responseCode = "404", description = "MSG41: no such member, banned, or blocking the caller")
    @GetMapping(MEMBER)
    public MemberProfileResponse profile(@AuthenticationPrincipal Jwt caller, @PathVariable UUID memberId) {
        return service.profile(optionalCaller(caller), memberId);
    }

    @Operation(summary = "Follow a member")
    @ApiResponse(responseCode = "200", description = "Following, with the member's follower count")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such member, banned, or blocking the caller")
    @ApiResponse(
            responseCode = "409",
            description = "MSG43: the caller's own account (`FOLLOW_SELF`); an administrator, or a member the caller"
                    + " blocks (`RELATION_NOT_ALLOWED`)")
    @PostMapping(MEMBER + "/follow")
    public EngagementResponse follow(@AuthenticationPrincipal Jwt caller, @PathVariable UUID memberId) {
        return service.follow(callerOf(caller), memberId);
    }

    @Operation(summary = "Stop following a member; following nobody is not an error")
    @ApiResponse(responseCode = "200", description = "Not following, with the member's follower count")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @DeleteMapping(MEMBER + "/follow")
    public EngagementResponse unfollow(@AuthenticationPrincipal Jwt caller, @PathVariable UUID memberId) {
        return service.unfollow(callerOf(caller), memberId);
    }

    @Operation(summary = "Block a member: no more follows either way, and their posts and comments are hidden")
    @ApiResponse(responseCode = "200", description = "Blocking, with how many members the caller blocks")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such member, banned, or blocking the caller")
    @ApiResponse(
            responseCode = "409",
            description = "MSG43: the caller's own account (`BLOCK_SELF`); an administrator (`RELATION_NOT_ALLOWED`)")
    @PostMapping(MEMBER + "/block")
    public EngagementResponse block(@AuthenticationPrincipal Jwt caller, @PathVariable UUID memberId) {
        return service.block(callerOf(caller), memberId);
    }

    @Operation(summary = "Stop blocking a member; blocking nobody is not an error")
    @ApiResponse(responseCode = "200", description = "Not blocking, with how many members the caller blocks")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @DeleteMapping(MEMBER + "/block")
    public EngagementResponse unblock(@AuthenticationPrincipal Jwt caller, @PathVariable UUID memberId) {
        return service.unblock(callerOf(caller), memberId);
    }

    @Operation(summary = "A member's followers, the most recent first; public")
    @ApiResponse(responseCode = "200", description = "One page of members")
    @ApiResponse(responseCode = "404", description = "MSG41: no such member, banned, or blocking the caller")
    @GetMapping(MEMBER + "/followers")
    public PageResponse<AuthorResponse> followers(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID memberId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return service.followers(optionalCaller(caller), memberId, page, pageSize);
    }

    @Operation(summary = "The members a member follows, the most recent first; public")
    @ApiResponse(responseCode = "200", description = "One page of members")
    @ApiResponse(responseCode = "404", description = "MSG41: no such member, banned, or blocking the caller")
    @GetMapping(MEMBER + "/following")
    public PageResponse<AuthorResponse> following(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID memberId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return service.following(optionalCaller(caller), memberId, page, pageSize);
    }

    @Operation(summary = "The members the caller blocks, the most recent first")
    @ApiResponse(responseCode = "200", description = "One page of members")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping("/api/v1/community/blocks")
    public PageResponse<AuthorResponse> blocked(
            @AuthenticationPrincipal Jwt caller,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return service.blocked(callerOf(caller), page, pageSize);
    }

    @Operation(summary = "Traders whose name starts with the text, to mention in a post or a comment")
    @ApiResponse(responseCode = "200", description = "At most ten members, alphabetical")
    @ApiResponse(responseCode = "400", description = "MSG15: the text is not 1 to 50 characters")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping("/api/v1/community/members")
    public List<AuthorResponse> search(@AuthenticationPrincipal Jwt caller, @RequestParam(required = false) String q) {
        return service.search(callerOf(caller), q);
    }

    @Operation(summary = "The members who count most in the community, for Top Influencers (D-125); public")
    @ApiResponse(responseCode = "200", description = "Up to `limit` members, by influence or analytical depth")
    @GetMapping("/api/v1/community/influencers")
    public List<InfluencerResponse> influencers(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "INFLUENCE (Social Influence, the default) or DEPTH (Analytical Depth)")
                    @RequestParam(required = false)
                    InfluencerRank rank,
            @RequestParam(required = false) Integer limit) {
        return service.influencers(optionalCaller(caller), rank, limit);
    }

    private static UUID optionalCaller(Jwt caller) {
        return caller == null ? null : callerOf(caller);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}
