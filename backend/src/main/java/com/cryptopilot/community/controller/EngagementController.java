package com.cryptopilot.community.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.community.dto.request.VoteRequest;
import com.cryptopilot.community.dto.response.EngagementResponse;
import com.cryptopilot.community.dto.response.VoteResponse;
import com.cryptopilot.community.model.enums.EngagementAction;
import com.cryptopilot.community.service.EngagementService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Likes and saves of SCR-24 and SCR-25 (UC-31, UC-32), Trader only. {@code POST} turns one on, {@code DELETE} turns it
 * off; repeating either changes nothing (BR-45). Each answers the state and the new count.
 *
 * <p>Rule: UC-31, UC-32; BR-45, BR-47; TECHNICAL_DESIGN 8.
 */
@Tag(
        name = "Reactions",
        description =
                "Likes and saves of posts and comments (UC-31, UC-32), Bullish and Bearish votes (D-125). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class EngagementController {

    private final EngagementService service;

    @Operation(summary = "Like a post (UC-31, BR-45)")
    @ApiResponse(responseCode = "200", description = "Liked, with the post's likes")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such post the caller may see")
    @PostMapping("/api/v1/posts/{id}/like")
    public EngagementResponse likePost(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return service.onPost(callerOf(caller), id, EngagementAction.LIKE, true);
    }

    @Operation(summary = "Take back the like of a post (UC-31)")
    @ApiResponse(responseCode = "200", description = "Not liked, with the post's likes")
    @ApiResponse(responseCode = "404", description = "MSG41: no such post the caller may see")
    @DeleteMapping("/api/v1/posts/{id}/like")
    public EngagementResponse unlikePost(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return service.onPost(callerOf(caller), id, EngagementAction.LIKE, false);
    }

    @Operation(summary = "Save a post to the Saved tab (UC-32, BR-45)")
    @ApiResponse(responseCode = "200", description = "Saved")
    @ApiResponse(responseCode = "404", description = "MSG41: no such post the caller may see")
    @PostMapping("/api/v1/posts/{id}/save")
    public EngagementResponse savePost(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return service.onPost(callerOf(caller), id, EngagementAction.SAVE, true);
    }

    @Operation(summary = "Remove a post from the Saved tab (UC-32)")
    @ApiResponse(responseCode = "200", description = "Not saved")
    @ApiResponse(responseCode = "404", description = "MSG41: no such post the caller may see")
    @DeleteMapping("/api/v1/posts/{id}/save")
    public EngagementResponse unsavePost(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return service.onPost(callerOf(caller), id, EngagementAction.SAVE, false);
    }

    @Operation(summary = "Vote a post Bullish or Bearish, replacing an earlier vote (D-125)")
    @ApiResponse(responseCode = "200", description = "Voted, with the post's Bullish and Bearish votes")
    @ApiResponse(responseCode = "400", description = "MSG01: no sentiment")
    @ApiResponse(responseCode = "404", description = "MSG41: no such post the caller may see")
    @PutMapping("/api/v1/posts/{id}/vote")
    public VoteResponse votePost(
            @AuthenticationPrincipal Jwt caller, @PathVariable UUID id, @Valid @RequestBody VoteRequest request) {
        return service.vote(callerOf(caller), id, request.sentiment());
    }

    @Operation(summary = "Take back the vote on a post (D-125); no vote is not an error")
    @ApiResponse(responseCode = "200", description = "Not voted, with the post's Bullish and Bearish votes")
    @ApiResponse(responseCode = "404", description = "MSG41: no such post the caller may see")
    @DeleteMapping("/api/v1/posts/{id}/vote")
    public VoteResponse unvotePost(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return service.vote(callerOf(caller), id, null);
    }

    @Operation(summary = "Like a comment (UC-31, BR-45)")
    @ApiResponse(responseCode = "200", description = "Liked, with the comment's likes")
    @ApiResponse(responseCode = "404", description = "MSG41: no such published comment the caller may see")
    @PostMapping("/api/v1/comments/{id}/like")
    public EngagementResponse likeComment(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return service.likeComment(callerOf(caller), id, true);
    }

    @Operation(summary = "Take back the like of a comment (UC-31)")
    @ApiResponse(responseCode = "200", description = "Not liked, with the comment's likes")
    @ApiResponse(responseCode = "404", description = "MSG41: no such published comment the caller may see")
    @DeleteMapping("/api/v1/comments/{id}/like")
    public EngagementResponse unlikeComment(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return service.likeComment(callerOf(caller), id, false);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}
