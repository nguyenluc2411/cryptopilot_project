package com.cryptopilot.billing.controller;

import com.cryptopilot.billing.dto.request.CreatePackageRequest;
import com.cryptopilot.billing.dto.request.PackageActiveRequest;
import com.cryptopilot.billing.dto.request.TierLimitsRequest;
import com.cryptopilot.billing.dto.request.UpdatePackageRequest;
import com.cryptopilot.billing.dto.response.AdminPackageResponse;
import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.billing.service.PackageAdminService;
import com.cryptopilot.common.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The subscription packages in the administration console (SCR-39, UC-42). Admin only, by the {@code /api/v1/admin/**}
 * rule of the security chain. Every change is audited.
 *
 * <p>Rule: SRS 3.11.5; BR-57, BR-62, BR-63; UC-42.
 */
@Tag(name = "Admin: packages", description = "Subscription packages and the limits of each plan tier.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/admin/packages")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AdminPackageController {

    private final PackageAdminService service;

    @Operation(summary = "Every package, inactive ones included, with the limits of its tier")
    @ApiResponse(responseCode = "200", description = "Lowest tier first, then by price")
    @GetMapping
    public List<AdminPackageResponse> list() {
        return service.list();
    }

    @Operation(summary = "One package")
    @ApiResponse(responseCode = "200", description = "The package")
    @ApiResponse(responseCode = "404", description = "MSG41: no such package")
    @GetMapping("/{packageId}")
    public AdminPackageResponse detail(@PathVariable UUID packageId) {
        return service.detail(packageId);
    }

    @Operation(
            summary = "Create a paid package (audited)",
            description = "PRO or PREMIUM; the package takes the limits its tier has now.")
    @ApiResponse(responseCode = "201", description = "The package")
    @ApiResponse(responseCode = "400", description = "MSG01: a missing or malformed field; MSG15: price or duration")
    @ApiResponse(
            responseCode = "409",
            description = "PACKAGE_CODE_TAKEN, MSG43: the code is taken; FREE_PACKAGE_FIXED, MSG43: tier FREE")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminPackageResponse create(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody CreatePackageRequest request) {
        return service.create(adminId(caller), request);
    }

    @Operation(
            summary = "Edit a package (audited)",
            description = "The name; for a paid package also the price, duration and sale flag. The FREE package"
                    + " takes a new name only. Orders already placed keep their amount.")
    @ApiResponse(responseCode = "200", description = "The package afterwards")
    @ApiResponse(responseCode = "400", description = "MSG01: a missing field; MSG15: price or duration")
    @ApiResponse(responseCode = "404", description = "MSG41: no such package")
    @ApiResponse(responseCode = "409", description = "FREE_PACKAGE_FIXED, MSG43: an offer field sent for FREE")
    @PutMapping("/{packageId}")
    public AdminPackageResponse update(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID packageId,
            @Valid @RequestBody UpdatePackageRequest request) {
        return service.update(adminId(caller), packageId, request);
    }

    @Operation(summary = "Put a package on offer or take it off (audited)")
    @ApiResponse(responseCode = "200", description = "The package afterwards")
    @ApiResponse(responseCode = "404", description = "MSG41: no such package")
    @ApiResponse(responseCode = "409", description = "FREE_PACKAGE_FIXED, MSG43: the FREE package stays active")
    @PatchMapping("/{packageId}/active")
    public AdminPackageResponse changeActive(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID packageId,
            @Valid @RequestBody PackageActiveRequest request) {
        return service.changeActive(adminId(caller), packageId, request.active());
    }

    @Operation(summary = "Delete a package no order was placed for (audited)")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "MSG41: no such package")
    @ApiResponse(
            responseCode = "409",
            description = "PACKAGE_IN_USE, MSG43: orders refer to it, deactivate it instead; FREE_PACKAGE_FIXED, MSG43")
    @DeleteMapping("/{packageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt caller, @PathVariable UUID packageId) {
        service.delete(adminId(caller), packageId);
    }

    @Operation(
            summary = "Save the limits of a plan tier to every package of the tier (audited)",
            description = "A null count means no limit. Traders' entitlements follow at once.")
    @ApiResponse(responseCode = "200", description = "The packages of the tier afterwards")
    @ApiResponse(responseCode = "400", description = "MSG01: an unknown tier or a missing field; MSG15: a count")
    @ApiResponse(responseCode = "404", description = "MSG41: the tier has no package")
    @PutMapping("/tiers/{tier}/limits")
    public List<AdminPackageResponse> updateTierLimits(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable PlanTier tier,
            @Valid @RequestBody TierLimitsRequest request) {
        return service.updateTierLimits(adminId(caller), tier, request);
    }

    private static UUID adminId(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}
