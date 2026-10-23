package com.cryptopilot.community.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.community.dto.response.MemberProfileResponse;
import com.cryptopilot.community.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Member pages, as on CoinMarketCap Community (D-99). Anyone reads a page. A member's posts are
 * {@code GET /posts?author=}.
 *
 * <p>Rule: D-99.
 */
@Tag(name = "Members", description = "Public member pages (D-99). Guest reads.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/community/members/{memberId}")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MemberController {

    private final MemberService service;

    @Operation(summary = "A member's public page; public")
    @ApiResponse(responseCode = "200", description = "Name, avatar, join date, posts and likes received")
    @ApiResponse(responseCode = "404", description = "MSG41: no such member, or banned")
    @GetMapping
    public MemberProfileResponse profile(@AuthenticationPrincipal Jwt caller, @PathVariable UUID memberId) {
        return service.profile(caller == null ? null : UUID.fromString(caller.getSubject()), memberId);
    }
}
