package com.cryptopilot.community.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.community.dto.request.PostRequest;
import com.cryptopilot.community.dto.response.PostDetailResponse;
import com.cryptopilot.community.dto.response.PostSummaryResponse;
import com.cryptopilot.community.model.enums.OfficialCategory;
import com.cryptopilot.community.service.AnnouncementService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The official announcements of the administration console (UC-44), Admin only. They are posts pinned above the
 * community feed (BR-61); the form is the one of SCR-26.
 *
 * <p>Rule: UC-44; BR-47, BR-61; SRS 3.7.2; TECHNICAL_DESIGN 2 (module rule 6), 8.
 */
@Tag(name = "Announcements", description = "Official posts pinned above the community feed (UC-44). Admin.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/admin/announcements")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AnnouncementController {

    private final AnnouncementService service;

    @Operation(summary = "One page of the announcements that are not deleted, newest first")
    @ApiResponse(responseCode = "200", description = "Published and hidden announcements")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping
    public PageResponse<PostSummaryResponse> list(
            @AuthenticationPrincipal Jwt caller,
            @RequestParam(required = false) OfficialCategory category,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return service.list(callerOf(caller), category, page, pageSize);
    }

    @Operation(summary = "Publish an announcement (UC-44)")
    @ApiResponse(responseCode = "201", description = "The announcement, pinned at once; the client shows MSG33")
    @ApiResponse(responseCode = "400", description = "MSG01: `errors` names each rejected field")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PostDetailResponse create(@AuthenticationPrincipal Jwt caller, @Valid @RequestBody PostRequest request) {
        return service.create(callerOf(caller), request);
    }

    @Operation(summary = "Edit an announcement")
    @ApiResponse(responseCode = "200", description = "The announcement")
    @ApiResponse(responseCode = "400", description = "MSG01: `errors` names each rejected field")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such announcement, or it is deleted")
    @PutMapping("/{id}")
    public PostDetailResponse update(
            @AuthenticationPrincipal Jwt caller, @PathVariable UUID id, @Valid @RequestBody PostRequest request) {
        return service.update(callerOf(caller), id, request);
    }

    @Operation(summary = "Delete an announcement (BR-47)")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such announcement, or it is deleted")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        service.delete(callerOf(caller), id);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}
