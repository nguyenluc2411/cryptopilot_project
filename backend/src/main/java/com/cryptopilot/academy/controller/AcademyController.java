package com.cryptopilot.academy.controller;

import com.cryptopilot.academy.dto.response.ArticleDetailResponse;
import com.cryptopilot.academy.dto.response.ArticleSummaryResponse;
import com.cryptopilot.academy.dto.response.TermResponse;
import com.cryptopilot.academy.dto.response.TopicResponse;
import com.cryptopilot.academy.model.ArticleFilter;
import com.cryptopilot.academy.model.enums.ArticleLevel;
import com.cryptopilot.academy.service.AcademyService;
import com.cryptopilot.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Academy a Guest reads: topics, published lessons, the beginner path and the glossary. Read-only and open to
 * anyone, as the filter chain grants {@code GET /api/v1/academy/**}.
 *
 * <p>Rule: T-114.
 */
@Tag(name = "Academy", description = "Lessons of crypto theory and the glossary (T-114). Guest.")
@RestController
@RequestMapping("/api/v1/academy")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AcademyController {

    private final AcademyService service;

    @Operation(summary = "Every topic of the Academy, in display order")
    @ApiResponse(responseCode = "200", description = "The topics with the number of their published lessons")
    @GetMapping("/topics")
    public List<TopicResponse> topics() {
        return service.topics();
    }

    @Operation(summary = "One page of published lessons, in reading order")
    @ApiResponse(responseCode = "200", description = "The cards of the page")
    @ApiResponse(responseCode = "400", description = "MSG01: an unknown level; MSG15: q, page or pageSize")
    @GetMapping("/articles")
    public PageResponse<ArticleSummaryResponse> articles(
            @Parameter(description = "Only lessons of the topic with this slug") @RequestParam(required = false)
                    String topic,
            @Parameter(description = "BEGINNER, INTERMEDIATE or ADVANCED") @RequestParam(required = false)
                    ArticleLevel level,
            @Parameter(description = "Search in titles and summaries, 2 to 100 characters")
                    @RequestParam(required = false)
                    String q,
            @Parameter(description = "Page from 1; default 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "1 to 100; default 20") @RequestParam(required = false) Integer pageSize) {
        return service.articles(new ArticleFilter(topic, level, q), page, pageSize);
    }

    @Operation(summary = "A published lesson with its body, its neighbours and related lessons")
    @ApiResponse(responseCode = "200", description = "The lesson")
    @ApiResponse(responseCode = "404", description = "MSG41: no published lesson with this slug")
    @GetMapping("/articles/{slug}")
    public ArticleDetailResponse article(@PathVariable String slug) {
        return service.article(slug);
    }

    @Operation(summary = "The lessons of the beginner path, in order")
    @ApiResponse(responseCode = "200", description = "The path")
    @GetMapping("/path")
    public List<ArticleSummaryResponse> beginnerPath() {
        return service.beginnerPath();
    }

    @Operation(summary = "The glossary, in alphabetical order")
    @ApiResponse(responseCode = "200", description = "The terms")
    @ApiResponse(responseCode = "400", description = "MSG15: q or letter")
    @GetMapping("/terms")
    public List<TermResponse> terms(
            @Parameter(description = "Search in terms and definitions, 2 to 100 characters")
                    @RequestParam(required = false)
                    String q,
            @Parameter(description = "Only terms starting with this letter, case ignored")
                    @RequestParam(required = false)
                    String letter) {
        return service.terms(q, letter);
    }
}
