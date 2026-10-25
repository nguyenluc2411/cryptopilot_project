package com.cryptopilot.news.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.news.dto.response.NewsArticleDetailResponse;
import com.cryptopilot.news.dto.response.NewsArticleResponse;
import com.cryptopilot.news.service.NewsFeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SCR-28 News Feed and the news blocks of SCR-08, SCR-10 and SCR-11 (UC-34). Traders only, by the
 * {@code /api/v1/news/**} rule of the security chain; every plan reads the same (D-130).
 *
 * <p>The count of articles per sentiment is the administrators' (D-130), on the console.
 *
 * <p>Rule: UC-34; SRS 3.8.1; D-126, D-129, D-130.
 */
@Tag(name = "News", description = "Aggregated crypto news with AI summary and sentiment, SCR-28 (UC-34).")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/news")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class NewsController {

    private final NewsFeedService service;

    @Operation(summary = "The news feed, newest first (UC-34)")
    @ApiResponse(
            responseCode = "200",
            description = "One page: title (Vietnamese once summarized), source, link, publish time, summary (AI, else"
                    + " the excerpt), sentiment and coin tags")
    @ApiResponse(
            responseCode = "400",
            description = "MSG15: sentiment is not BULLISH, NEUTRAL or BEARISH, range is not 24h, 7d or 30d, or"
                    + " page/pageSize is out of range")
    @GetMapping
    public PageResponse<NewsArticleResponse> feed(
            @Parameter(description = "Only articles tagged with this coin symbol, e.g. BTC")
                    @RequestParam(required = false)
                    String coin,
            @Parameter(description = "BULLISH, NEUTRAL or BEARISH: only articles with this sentiment")
                    @RequestParam(required = false)
                    String sentiment,
            @Parameter(description = "24h, 7d or 30d; 7d by default") @RequestParam(required = false) String range,
            @Parameter(description = "From 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "1 to 100, 20 by default") @RequestParam(required = false) Integer pageSize) {
        return service.feed(coin, sentiment, range, page, pageSize);
    }

    @Operation(
            summary = "One article for its page in the app: the article written again, cover image and source (D-129)")
    @ApiResponse(
            responseCode = "200",
            description = "Title, source, original link, publish time, summary, body (AI, else null), sentiment,"
                    + " coin tags and cover image")
    @ApiResponse(responseCode = "404", description = "No such article, or an administrator hid it")
    @GetMapping("/{articleId}")
    public NewsArticleDetailResponse article(@PathVariable UUID articleId) {
        return service.article(articleId);
    }
}
