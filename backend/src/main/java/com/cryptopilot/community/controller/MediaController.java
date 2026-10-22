package com.cryptopilot.community.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.community.dto.request.UploadSignatureRequest;
import com.cryptopilot.community.dto.response.UploadSignatureResponse;
import com.cryptopilot.community.service.MediaService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signed upload forms for the files of posts (SCR-26) and announcements (UC-44). The browser uploads the file straight
 * to the media storage with the form and names its {@code publicId} in the post; the account secret never leaves the
 * backend (D-98).
 *
 * <p>Rule: BR-43, BR-44; D-98; TECHNICAL_DESIGN 8.
 */
@Tag(name = "Media", description = "Signed forms to upload the images and videos of posts (D-98).")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MediaController {

    private final MediaService service;

    @Operation(summary = "A signed form to upload one file of a post (SCR-26), Trader")
    @ApiResponse(responseCode = "200", description = "The form fields and the limits of BR-44")
    @ApiResponse(responseCode = "400", description = "MSG01: no or an unknown mediaType")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: a video on a plan without video posting (BR-43)")
    @ApiResponse(responseCode = "503", description = "MSG43: the media storage is not available")
    @PostMapping("/api/v1/media/upload-signature")
    public UploadSignatureResponse signForTrader(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody UploadSignatureRequest request) {
        return service.signForTrader(callerOf(caller), request.mediaType());
    }

    @Operation(summary = "A signed form to upload one file of an announcement (UC-44), Admin")
    @ApiResponse(responseCode = "200", description = "The form fields and the limits of BR-44")
    @ApiResponse(responseCode = "400", description = "MSG01: no or an unknown mediaType")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "503", description = "MSG43: the media storage is not available")
    @PostMapping("/api/v1/admin/media/upload-signature")
    public UploadSignatureResponse signForAdmin(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody UploadSignatureRequest request) {
        return service.signForAdmin(callerOf(caller), request.mediaType());
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}
