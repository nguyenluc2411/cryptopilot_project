package com.cryptopilot.community.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.community.dto.request.ModerationDecisionRequest;
import com.cryptopilot.community.dto.response.CommentModerationCaseResponse;
import com.cryptopilot.community.dto.response.CommentModerationDecisionResponse;
import com.cryptopilot.community.dto.response.CommentQueueItemResponse;
import com.cryptopilot.community.dto.response.ModeratedCommentResponse;
import com.cryptopilot.community.dto.response.ModerationCaseResponse;
import com.cryptopilot.community.dto.response.ModerationDecisionResponse;
import com.cryptopilot.community.dto.response.PostDetailResponse;
import com.cryptopilot.community.dto.response.QueueItemResponse;
import com.cryptopilot.community.service.CommentModerationService;
import com.cryptopilot.community.service.ModerationService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The report moderation queue of SCR-36 (UC-43), Admin only. Served by {@code community} because the decisions change
 * community tables (TECHNICAL_DESIGN 2, module rule 6).
 *
 * <p>Rule: UC-43; BR-06, BR-47, BR-57; SRS 3.11.3; TECHNICAL_DESIGN 8.
 */
@Tag(name = "Moderation", description = "The report moderation queues of SCR-36 for posts and comments (UC-43). Admin.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ModerationController {

    private final ModerationService service;
    private final CommentModerationService comments;

    @Operation(summary = "The posts with open reports, reported longest ago first (SCR-36)")
    @ApiResponse(responseCode = "200", description = "One row per post, with its reports counted and their reasons")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping("/reports-queue")
    public PageResponse<QueueItemResponse> queue(
            @AuthenticationPrincipal Jwt caller,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return service.queue(callerOf(caller), page, pageSize);
    }

    @Operation(summary = "Open a reported post; its OPEN reports become REVIEWING")
    @ApiResponse(responseCode = "200", description = "The post, whatever its status, and its open reports")
    @ApiResponse(responseCode = "404", description = "MSG41: no such post, or it has no open report")
    @GetMapping("/reports-queue/{postId}")
    public ModerationCaseResponse open(@AuthenticationPrincipal Jwt caller, @PathVariable UUID postId) {
        return service.open(callerOf(caller), postId);
    }

    @Operation(summary = "Decide all open reports of a post (UC-43)")
    @ApiResponse(responseCode = "200", description = "Decided and audited; the author and reporters are told")
    @ApiResponse(responseCode = "400", description = "MSG01: no or an unknown action, or a note over 500 characters")
    @ApiResponse(responseCode = "404", description = "MSG41: no such post, or it has no open report")
    @ApiResponse(
            responseCode = "409",
            description =
                    "MSG39: BAN_USER on an administrator's post (BR-58); MSG43 (`DATA_CONFLICT`): the post changed"
                            + " meanwhile, e.g. hidden by a ban of its author just decided; deciding again succeeds")
    @PostMapping("/reports-queue/{postId}/decision")
    public ModerationDecisionResponse decide(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID postId,
            @Valid @RequestBody ModerationDecisionRequest request) {
        return service.decide(callerOf(caller), postId, request);
    }

    @Operation(summary = "Any post, whatever its status (BR-47)")
    @ApiResponse(responseCode = "200", description = "The post")
    @ApiResponse(responseCode = "404", description = "MSG41: no such post")
    @GetMapping("/posts/{id}")
    public PostDetailResponse post(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return service.post(callerOf(caller), id);
    }

    @Operation(summary = "Show a hidden post again (UC-43)")
    @ApiResponse(responseCode = "200", description = "Published again and audited")
    @ApiResponse(responseCode = "404", description = "MSG41: no such post")
    @ApiResponse(
            responseCode = "409",
            description =
                    "MSG43: the post is not hidden (`POST_NOT_HIDDEN`), or its author is banned (`AUTHOR_BANNED`)")
    @PostMapping("/posts/{id}/restore")
    public PostDetailResponse restore(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return service.restore(callerOf(caller), id);
    }

    @Operation(summary = "The comments with open reports, reported longest ago first (D-125)")
    @ApiResponse(responseCode = "200", description = "One row per comment, with its reports counted and their reasons")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping("/comment-reports-queue")
    public PageResponse<CommentQueueItemResponse> commentQueue(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return comments.queue(page, pageSize);
    }

    @Operation(summary = "Open a reported comment; its OPEN reports become REVIEWING (D-125)")
    @ApiResponse(
            responseCode = "200",
            description = "The comment as it is now, and each open report with the text it had")
    @ApiResponse(responseCode = "404", description = "MSG41: no such comment, or it has no open report")
    @GetMapping("/comment-reports-queue/{commentId}")
    public CommentModerationCaseResponse openComment(
            @AuthenticationPrincipal Jwt caller, @PathVariable UUID commentId) {
        return comments.open(callerOf(caller), commentId);
    }

    @Operation(summary = "Decide all open reports of a comment (D-125)")
    @ApiResponse(responseCode = "200", description = "Decided and audited; the author and reporters are told")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01: no action, HIDE_POST or REMOVE_POST, or a note over 500 characters")
    @ApiResponse(responseCode = "404", description = "MSG41: no such comment, or it has no open report")
    @ApiResponse(
            responseCode = "409",
            description = "MSG39: BAN_USER on an administrator's comment (BR-58); MSG43 (`DATA_CONFLICT`): the comment"
                    + " changed meanwhile, e.g. hidden by a ban of its author just decided; deciding again succeeds")
    @PostMapping("/comment-reports-queue/{commentId}/decision")
    public CommentModerationDecisionResponse decideComment(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID commentId,
            @Valid @RequestBody ModerationDecisionRequest request) {
        return comments.decide(callerOf(caller), commentId, request);
    }

    @Operation(summary = "Show a hidden comment again (D-125)")
    @ApiResponse(responseCode = "200", description = "Published again and audited")
    @ApiResponse(responseCode = "404", description = "MSG41: no such comment")
    @ApiResponse(
            responseCode = "409",
            description = "MSG43: the comment is not hidden (`COMMENT_NOT_HIDDEN`), or its author is banned"
                    + " (`AUTHOR_BANNED`)")
    @PostMapping("/comments/{id}/restore")
    public ModeratedCommentResponse restoreComment(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return comments.restore(callerOf(caller), id);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}
