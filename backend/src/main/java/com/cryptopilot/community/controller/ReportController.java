package com.cryptopilot.community.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.community.dto.request.ReportRequest;
import com.cryptopilot.community.dto.response.ReportResponse;
import com.cryptopilot.community.service.ReportService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The report dialog of SCR-27 (UC-33) for posts and comments (D-99), Trader only.
 *
 * <p>Rule: UC-33; BR-46; SRS 3.7.4; TECHNICAL_DESIGN 8.
 */
@Tag(name = "Reports", description = "Violation reports of posts and comments (UC-33, D-99). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ReportController {

    private final ReportService service;

    @Operation(summary = "Report a post that breaks the community rules (UC-33, BR-46)")
    @ApiResponse(responseCode = "201", description = "Filed OPEN; the client shows MSG34 (submitted)")
    @ApiResponse(responseCode = "400", description = "MSG01: no reason, or OTHER without a description")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such post the caller may see")
    @ApiResponse(
            responseCode = "409",
            description = "MSG34: the caller already reported it (`POST_ALREADY_REPORTED`); MSG43: the caller's own"
                    + " post (`POST_REPORT_OWN_POST`)")
    @PostMapping("/api/v1/posts/{id}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportResponse report(
            @AuthenticationPrincipal Jwt caller, @PathVariable UUID id, @Valid @RequestBody ReportRequest request) {
        return service.report(UUID.fromString(caller.getSubject()), id, request);
    }

    @Operation(summary = "Report a comment that breaks the community rules (D-99)")
    @ApiResponse(responseCode = "201", description = "Filed OPEN with the comment's text; the client shows MSG34")
    @ApiResponse(responseCode = "400", description = "MSG01: no reason, or OTHER without a description")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such published comment the caller may see")
    @ApiResponse(
            responseCode = "409",
            description = "MSG34: the caller already reported it (`COMMENT_ALREADY_REPORTED`); MSG43: the caller's own"
                    + " comment (`COMMENT_REPORT_OWN_COMMENT`)")
    @PostMapping("/api/v1/comments/{id}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportResponse reportComment(
            @AuthenticationPrincipal Jwt caller, @PathVariable UUID id, @Valid @RequestBody ReportRequest request) {
        return service.reportComment(UUID.fromString(caller.getSubject()), id, request);
    }
}
