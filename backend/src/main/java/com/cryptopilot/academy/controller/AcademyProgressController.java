package com.cryptopilot.academy.controller;

import com.cryptopilot.academy.dto.response.AcademyProgressResponse;
import com.cryptopilot.academy.dto.response.CompletedLessonResponse;
import com.cryptopilot.academy.service.AcademyProgressService;
import com.cryptopilot.common.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A Trader's progress in the Academy: the lessons they marked as learned. Under {@code /me}, so the filter chain
 * admits a Trader only.
 *
 * <p>Rule: T-114.
 */
@Tag(name = "Academy progress", description = "Lessons a Trader marked as learned (T-114). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/me/academy/progress")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AcademyProgressController {

    private final AcademyProgressService service;

    @Operation(summary = "The caller's progress in the Academy")
    @ApiResponse(responseCode = "200", description = "The lessons marked as learned and the totals")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping
    public AcademyProgressResponse progress(@AuthenticationPrincipal Jwt caller) {
        return service.progress(callerOf(caller));
    }

    @Operation(summary = "Marks a published lesson as learned; idempotent")
    @ApiResponse(responseCode = "200", description = "The lesson and when it was first marked")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no published lesson with this slug")
    @PostMapping("/{slug}")
    public CompletedLessonResponse mark(@AuthenticationPrincipal Jwt caller, @PathVariable String slug) {
        return service.mark(callerOf(caller), slug);
    }

    @Operation(summary = "Takes the mark off a published lesson; idempotent")
    @ApiResponse(responseCode = "204", description = "The lesson is not marked")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no published lesson with this slug")
    @DeleteMapping("/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unmark(@AuthenticationPrincipal Jwt caller, @PathVariable String slug) {
        service.unmark(callerOf(caller), slug);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}
