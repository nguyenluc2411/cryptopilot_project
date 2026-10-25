package com.cryptopilot.academy.controller;

import com.cryptopilot.academy.dto.request.ArticleRequest;
import com.cryptopilot.academy.dto.request.TermRequest;
import com.cryptopilot.academy.dto.response.AdminArticleResponse;
import com.cryptopilot.academy.dto.response.AdminArticleSummaryResponse;
import com.cryptopilot.academy.dto.response.AdminTermResponse;
import com.cryptopilot.academy.model.enums.ArticleStatus;
import com.cryptopilot.academy.service.AcademyAdminService;
import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
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
 * The Academy in the administration console: lessons in every status, their editor, publishing and hiding, and the
 * glossary. Admin only, under {@code /admin}; every change is audited (BR-57).
 *
 * <p>Rule: BR-57; T-114.
 */
@Tag(name = "Academy administration", description = "Lessons and glossary of the Academy (T-114). Admin.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/admin/academy")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AcademyAdminController {

    private final AcademyAdminService service;

    @Operation(summary = "One page of lessons in every status, the latest change first")
    @ApiResponse(responseCode = "200", description = "The rows of the page")
    @ApiResponse(responseCode = "400", description = "MSG01: an unknown status; MSG15: q, page or pageSize")
    @GetMapping("/articles")
    public PageResponse<AdminArticleSummaryResponse> articles(
            @Parameter(description = "DRAFT, PUBLISHED or HIDDEN") @RequestParam(required = false) ArticleStatus status,
            @Parameter(description = "Only lessons of the topic with this slug") @RequestParam(required = false)
                    String topic,
            @Parameter(description = "Search in titles and slugs, 2 to 100 characters") @RequestParam(required = false)
                    String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return service.articles(status, topic, q, page, pageSize);
    }

    @Operation(summary = "A lesson, whatever its status, for the editor")
    @ApiResponse(responseCode = "200", description = "The lesson")
    @ApiResponse(responseCode = "404", description = "MSG41: no such lesson")
    @GetMapping("/articles/{id}")
    public AdminArticleResponse article(@PathVariable UUID id) {
        return service.article(id);
    }

    @Operation(summary = "A new lesson, as a draft")
    @ApiResponse(responseCode = "201", description = "The draft")
    @ApiResponse(responseCode = "400", description = "MSG01 or MSG15: a field is missing or invalid")
    @ApiResponse(
            responseCode = "409",
            description = "ACADEMY_SLUG_TAKEN or ACADEMY_PATH_ORDER_TAKEN: the slug or the place is taken")
    @PostMapping("/articles")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminArticleResponse create(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody ArticleRequest request) {
        return service.create(callerOf(caller), request);
    }

    @Operation(summary = "New content for a lesson; its status stays")
    @ApiResponse(responseCode = "200", description = "The lesson")
    @ApiResponse(responseCode = "400", description = "MSG01 or MSG15: a field is missing or invalid")
    @ApiResponse(responseCode = "404", description = "MSG41: no such lesson")
    @ApiResponse(
            responseCode = "409",
            description = "ACADEMY_SLUG_TAKEN or ACADEMY_PATH_ORDER_TAKEN: the slug or the place is taken")
    @PutMapping("/articles/{id}")
    public AdminArticleResponse update(
            @AuthenticationPrincipal Jwt caller, @PathVariable UUID id, @Valid @RequestBody ArticleRequest request) {
        return service.update(callerOf(caller), id, request);
    }

    @Operation(summary = "Shows a lesson to everyone; idempotent")
    @ApiResponse(responseCode = "200", description = "The lesson, published")
    @ApiResponse(responseCode = "404", description = "MSG41: no such lesson")
    @PostMapping("/articles/{id}/publish")
    public AdminArticleResponse publish(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return service.publish(callerOf(caller), id);
    }

    @Operation(summary = "Takes a lesson down, keeping it; idempotent")
    @ApiResponse(responseCode = "200", description = "The lesson, hidden")
    @ApiResponse(responseCode = "404", description = "MSG41: no such lesson")
    @PostMapping("/articles/{id}/hide")
    public AdminArticleResponse hide(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return service.hide(callerOf(caller), id);
    }

    @Operation(summary = "Deletes a lesson that is not published")
    @ApiResponse(responseCode = "204", description = "Deleted, with its readers' marks")
    @ApiResponse(responseCode = "404", description = "MSG41: no such lesson")
    @ApiResponse(responseCode = "409", description = "ACADEMY_ARTICLE_PUBLISHED: hide the lesson first")
    @DeleteMapping("/articles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        service.delete(callerOf(caller), id);
    }

    @Operation(summary = "The glossary, in alphabetical order")
    @ApiResponse(responseCode = "200", description = "The terms")
    @ApiResponse(responseCode = "400", description = "MSG15: q")
    @GetMapping("/terms")
    public List<AdminTermResponse> terms(
            @Parameter(description = "Search in terms, 2 to 100 characters") @RequestParam(required = false) String q) {
        return service.terms(q);
    }

    @Operation(summary = "A new term")
    @ApiResponse(responseCode = "201", description = "The term")
    @ApiResponse(responseCode = "400", description = "MSG01: a field is missing or invalid, or no such lesson")
    @ApiResponse(responseCode = "409", description = "ACADEMY_SLUG_TAKEN: the slug is taken")
    @PostMapping("/terms")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminTermResponse createTerm(@AuthenticationPrincipal Jwt caller, @Valid @RequestBody TermRequest request) {
        return service.createTerm(callerOf(caller), request);
    }

    @Operation(summary = "New content for a term")
    @ApiResponse(responseCode = "200", description = "The term")
    @ApiResponse(responseCode = "400", description = "MSG01: a field is missing or invalid, or no such lesson")
    @ApiResponse(responseCode = "404", description = "MSG41: no such term")
    @ApiResponse(responseCode = "409", description = "ACADEMY_SLUG_TAKEN: the slug is taken")
    @PutMapping("/terms/{id}")
    public AdminTermResponse updateTerm(
            @AuthenticationPrincipal Jwt caller, @PathVariable UUID id, @Valid @RequestBody TermRequest request) {
        return service.updateTerm(callerOf(caller), id, request);
    }

    @Operation(summary = "Deletes a term")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "MSG41: no such term")
    @DeleteMapping("/terms/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTerm(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        service.deleteTerm(callerOf(caller), id);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}
