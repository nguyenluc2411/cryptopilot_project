package com.cryptopilot.news.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.news.dto.request.NewsSourceRequest;
import com.cryptopilot.news.dto.response.NewsCrawlLogResponse;
import com.cryptopilot.news.dto.response.NewsSourceResponse;
import com.cryptopilot.news.service.NewsSourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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
 * SCR-41 of the administration console: the RSS sources the crawl reads and the crawl log of each (UC-49). Admin only,
 * by the {@code /api/v1/admin/**} rule of the security chain.
 *
 * <p>Rule: UC-49; BR-49, BR-57; NSF-10.
 */
@Tag(name = "Admin: news sources", description = "RSS sources and their crawl log, SCR-41 (UC-49).")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/admin/news-sources")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AdminNewsSourceController {

    private final NewsSourceService service;

    @Operation(summary = "The news sources, by name, each with its latest crawl (SCR-41)")
    @ApiResponse(responseCode = "200", description = "Every source")
    @GetMapping
    public List<NewsSourceResponse> list() {
        return service.list();
    }

    @Operation(summary = "Add a news source; its feed is read once before it is saved (UC-49)")
    @ApiResponse(responseCode = "201", description = "The source")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01 or MSG15: `errors` names each rejected field; NEWS_FEED_UNREADABLE: the feed could"
                    + " not be read; the interval is at least 15 minutes (BR-49)")
    @ApiResponse(responseCode = "409", description = "NEWS_SOURCE_ALREADY_REGISTERED: another source reads this feed")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NewsSourceResponse create(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody NewsSourceRequest request) {
        return service.create(UUID.fromString(caller.getSubject()), request);
    }

    @Operation(summary = "One news source")
    @ApiResponse(responseCode = "200", description = "The source")
    @ApiResponse(responseCode = "404", description = "MSG41: no such source")
    @GetMapping("/{id}")
    public NewsSourceResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @Operation(summary = "Replace every setting of a news source (UC-49)")
    @ApiResponse(responseCode = "200", description = "The source; a changed feed address is read again")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01 or MSG15: `errors` names each rejected field; NEWS_FEED_UNREADABLE: the feed could"
                    + " not be read")
    @ApiResponse(responseCode = "404", description = "MSG41: no such source")
    @ApiResponse(responseCode = "409", description = "NEWS_SOURCE_ALREADY_REGISTERED: another source reads this feed")
    @PutMapping("/{id}")
    public NewsSourceResponse update(
            @AuthenticationPrincipal Jwt caller, @PathVariable UUID id, @Valid @RequestBody NewsSourceRequest request) {
        return service.update(UUID.fromString(caller.getSubject()), id, request);
    }

    @Operation(summary = "Delete a news source no article came from, with its crawl log")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "MSG41: no such source")
    @ApiResponse(responseCode = "409", description = "NEWS_SOURCE_IN_USE: articles came from it; switch it off")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        service.delete(UUID.fromString(caller.getSubject()), id);
    }

    @Operation(summary = "The crawl log of a news source, the latest first (UC-49)")
    @ApiResponse(responseCode = "200", description = "One page: time, status, articles found and inserted, error")
    @ApiResponse(responseCode = "400", description = "MSG15: page or pageSize out of range")
    @ApiResponse(responseCode = "404", description = "MSG41: no such source")
    @GetMapping("/{id}/crawl-logs")
    public PageResponse<NewsCrawlLogResponse> crawlLogs(
            @PathVariable UUID id,
            @Parameter(description = "From 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "1 to 100, 20 by default") @RequestParam(required = false) Integer pageSize) {
        return service.crawlLogs(id, page, pageSize);
    }
}
