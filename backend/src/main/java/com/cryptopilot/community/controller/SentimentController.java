package com.cryptopilot.community.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.community.dto.request.SentimentVoteRequest;
import com.cryptopilot.community.dto.response.CoinSentimentResponse;
import com.cryptopilot.community.service.SentimentService;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * The community sentiment of a coin, as on CoinMarketCap Community (D-99): anyone reads it, a Trader votes once a day.
 * The client shows the disclaimer with it (BR-60, MSG42).
 *
 * <p>Rule: BR-60; D-99.
 */
@Tag(name = "Sentiment", description = "Bullish or bearish: the community's daily vote on a coin (D-99).")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/posts/coins/{symbol}/sentiment")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SentimentController {

    private final SentimentService service;

    @Operation(summary = "Today's sentiment of a coin; public")
    @ApiResponse(responseCode = "200", description = "Today's votes, the caller's vote and the labels of recent posts")
    @ApiResponse(responseCode = "404", description = "MSG41: no such coin")
    @GetMapping
    public CoinSentimentResponse coin(@AuthenticationPrincipal Jwt caller, @PathVariable String symbol) {
        return service.coin(caller == null ? null : UUID.fromString(caller.getSubject()), symbol);
    }

    @Operation(summary = "Vote bullish or bearish on a coin for today; a second vote today replaces the first")
    @ApiResponse(responseCode = "200", description = "Today's sentiment with the caller's vote")
    @ApiResponse(responseCode = "400", description = "MSG01: no or an unknown sentiment")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no listed pair trades the coin")
    @PostMapping
    public CoinSentimentResponse vote(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable String symbol,
            @Valid @RequestBody SentimentVoteRequest request) {
        return service.vote(UUID.fromString(caller.getSubject()), symbol, request.sentiment());
    }
}
