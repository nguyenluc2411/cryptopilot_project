package com.cryptopilot.news.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.news.dto.response.AdminNewsArticleResponse;
import com.cryptopilot.news.service.NewsArticleAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The collected articles in the administration console (T-080b): what NSF-11 did with each, hiding one from the feed,
 * and asking for its summary again. Admin only, by the {@code /api/v1/admin/**} rule of the security chain.
 *
 * <p>Rule: NSF-11; BR-57; D-128.
 */
@Tag(name = "Admin: news articles", description = "Collected articles, their AI summary status and moderation.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/admin/news-articles")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AdminNewsArticleController {

    private final NewsArticleAdminService service;

    @Operation(summary = "The articles, newest first, hidden ones included, with their summary status")
    @ApiResponse(responseCode = "200", description = "One page")
    @ApiResponse(responseCode = "400", description = "MSG15: unknown status, or page/pageSize out of range")
    @GetMapping
    public PageResponse<AdminNewsArticleResponse> list(
            @Parameter(description = "PENDING, FAILED, SUMMARIZED or HIDDEN") @RequestParam(required = false)
                    String status,
            @Parameter(description = "Only the articles of this source") @RequestParam(required = false) UUID sourceId,
            @Parameter(description = "From 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "1 to 100, 20 by default") @RequestParam(required = false) Integer pageSize) {
        return service.list(status, sourceId, page, pageSize);
    }

    @Operation(summary = "Hide an article from the news feed and the sentiment summary (audited)")
    @ApiResponse(responseCode = "200", description = "The article, HIDDEN")
    @ApiResponse(responseCode = "404", description = "MSG41: no such article")
    @PostMapping("/{id}/hide")
    public AdminNewsArticleResponse hide(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return service.hide(UUID.fromString(caller.getSubject()), id);
    }

    @Operation(summary = "Show a hidden article in the news feed again (audited)")
    @ApiResponse(responseCode = "200", description = "The article")
    @ApiResponse(responseCode = "404", description = "MSG41: no such article")
    @PostMapping("/{id}/restore")
    public AdminNewsArticleResponse restore(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return service.restore(UUID.fromString(caller.getSubject()), id);
    }

    @Operation(
            summary = "Summarize an article now, replacing its summary (D-128)",
            description = "Waits for the model, up to a couple of minutes. A refused answer leaves the article without"
                    + " a summary and its reason in summaryError.")
    @ApiResponse(responseCode = "200", description = "The article: SUMMARIZED, or its summaryError")
    @ApiResponse(responseCode = "404", description = "MSG41: no such article")
    @ApiResponse(
            responseCode = "503",
            description = "NEWS_SUMMARY_UNAVAILABLE, MSG31: the AI Service is not configured, down or rate limited")
    @PostMapping("/{id}/summarize")
    public AdminNewsArticleResponse summarize(@PathVariable UUID id) {
        return service.summarize(id);
    }
}
