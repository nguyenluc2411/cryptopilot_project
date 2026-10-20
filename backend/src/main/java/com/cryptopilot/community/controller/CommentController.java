package com.cryptopilot.community.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.community.dto.request.CommentRequest;
import com.cryptopilot.community.dto.response.CommentResponse;
import com.cryptopilot.community.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The comments of SCR-25 (UC-30), Trader only. A post or comment the caller may not see or change answers 404.
 *
 * <p>Rule: UC-30; BR-47; SRS 3.7.3; TECHNICAL_DESIGN 8.
 */
@Tag(name = "Comments", description = "Comments and replies of SCR-25 (UC-30). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class CommentController {

    private final CommentService service;

    @Operation(summary = "One page of a post's threads, oldest first, each with its replies (SCR-25)")
    @ApiResponse(responseCode = "200", description = "Threads; a deleted comment with replies has no content")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such post the caller may see (BR-47)")
    @GetMapping("/api/v1/posts/{postId}/comments")
    public PageResponse<CommentResponse> list(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID postId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return service.list(callerOf(caller), postId, page, pageSize);
    }

    @Operation(summary = "Comment on a post, or reply to a comment (UC-30)")
    @ApiResponse(responseCode = "201", description = "The comment")
    @ApiResponse(responseCode = "400", description = "MSG01: `errors` names each rejected field")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such post, or no such published comment on it")
    @PostMapping("/api/v1/posts/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse create(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID postId,
            @Valid @RequestBody CommentRequest request) {
        return service.create(callerOf(caller), postId, request);
    }

    @Operation(summary = "Edit one of the caller's comments (UC-30)")
    @ApiResponse(responseCode = "200", description = "The comment")
    @ApiResponse(responseCode = "400", description = "MSG01: `errors` names each rejected field")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such comment of the caller, or it is deleted")
    @PutMapping("/api/v1/comments/{id}")
    public CommentResponse update(
            @AuthenticationPrincipal Jwt caller, @PathVariable UUID id, @Valid @RequestBody CommentRequest request) {
        return service.update(callerOf(caller), id, request);
    }

    @Operation(summary = "Delete one of the caller's comments (UC-30, BR-47)")
    @ApiResponse(responseCode = "204", description = "Deleted; listed as deleted while it has replies")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such comment of the caller, or it is deleted")
    @DeleteMapping("/api/v1/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        service.delete(callerOf(caller), id);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}
