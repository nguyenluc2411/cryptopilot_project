package com.cryptopilot.user.controller;

import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.user.dto.request.AccountActionRequest;
import com.cryptopilot.user.dto.request.ChangeRoleRequest;
import com.cryptopilot.user.dto.response.AdminUserDetailResponse;
import com.cryptopilot.user.dto.response.AdminUserSummaryResponse;
import com.cryptopilot.user.model.enums.AccountStatus;
import com.cryptopilot.user.model.enums.Role;
import com.cryptopilot.user.service.AccountAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SCR-34 and SCR-35 of the administration console: user accounts (UC-42). Admin only, by the {@code /api/v1/admin/**}
 * rule of the security chain. The client asks for confirmation (MSG39) before each action.
 *
 * <p>Rule: SRS 3.11.2, UC-42; BR-05, BR-06, BR-57, BR-58.
 */
@Tag(name = "Admin: users", description = "Search accounts, lock, unlock, ban and change roles, SCR-34 and SCR-35.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AccountAdminController {

    private final AccountAdminService service;

    @Operation(summary = "One page of accounts, newest first (SCR-34)")
    @ApiResponse(responseCode = "200", description = "20 rows by default")
    @ApiResponse(responseCode = "400", description = "MSG01: an unknown filter value; MSG15: page or pageSize")
    @GetMapping
    public PageResponse<AdminUserSummaryResponse> search(
            @Parameter(description = "Part of the email or display name") @RequestParam(required = false) String q,
            @Parameter(description = "TRADER or ADMIN") @RequestParam(required = false) Role role,
            @Parameter(description = "ACTIVE, LOCKED or BANNED") @RequestParam(required = false) AccountStatus status,
            @Parameter(description = "The effective plan: FREE, PRO or PREMIUM") @RequestParam(required = false)
                    PlanTier plan,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return service.search(q, role, status, plan, page, pageSize);
    }

    @Operation(summary = "One account with its activity and latest audit entries (SCR-35)")
    @ApiResponse(responseCode = "200", description = "The account")
    @ApiResponse(responseCode = "404", description = "MSG41: no such account")
    @GetMapping("/{id}")
    public AdminUserDetailResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @Operation(summary = "Lock an ACTIVE account and end its sessions (BR-06)")
    @ApiResponse(responseCode = "200", description = "The account")
    @ApiResponse(responseCode = "409", description = "MSG39: not ACTIVE, one's own account, or the last active admin")
    @PostMapping("/{id}/lock")
    public AdminUserDetailResponse lock(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID id,
            @Valid @RequestBody AccountActionRequest request) {
        return service.lock(adminOf(caller), id, request.reason());
    }

    @Operation(summary = "Unlock a LOCKED account")
    @ApiResponse(responseCode = "200", description = "The account")
    @ApiResponse(responseCode = "409", description = "MSG39: not LOCKED")
    @PostMapping("/{id}/unlock")
    public AdminUserDetailResponse unlock(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID id,
            @Valid @RequestBody AccountActionRequest request) {
        return service.unlock(adminOf(caller), id, request.reason());
    }

    @Operation(summary = "Ban an account for good and end its sessions (BR-06)")
    @ApiResponse(responseCode = "200", description = "The account")
    @ApiResponse(
            responseCode = "409",
            description = "MSG39: banned already, one's own account, or the last active admin")
    @PostMapping("/{id}/ban")
    public AdminUserDetailResponse ban(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID id,
            @Valid @RequestBody AccountActionRequest request) {
        return service.ban(adminOf(caller), id, request.reason());
    }

    @Operation(summary = "Change the role and end the account's sessions")
    @ApiResponse(responseCode = "200", description = "The account")
    @ApiResponse(responseCode = "409", description = "MSG39: one's own account, or demoting the last active admin")
    @PutMapping("/{id}/role")
    public AdminUserDetailResponse changeRole(
            @AuthenticationPrincipal Jwt caller, @PathVariable UUID id, @Valid @RequestBody ChangeRoleRequest request) {
        return service.changeRole(adminOf(caller), id, request.role(), request.reason());
    }

    private static UUID adminOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}
