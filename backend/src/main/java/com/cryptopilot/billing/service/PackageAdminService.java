package com.cryptopilot.billing.service;

import com.cryptopilot.billing.dto.request.CreatePackageRequest;
import com.cryptopilot.billing.dto.request.TierLimitsRequest;
import com.cryptopilot.billing.dto.request.UpdatePackageRequest;
import com.cryptopilot.billing.dto.response.AdminPackageResponse;
import com.cryptopilot.billing.model.enums.PlanTier;
import java.util.List;
import java.util.UUID;

/**
 * The administrator's package console (SCR-39, UC-42): the packages with the limits of their tier, created, edited,
 * put on offer or taken off, deleted while no order refers to them, and the limits of a tier saved to all of its
 * packages. Every change is audited (BR-57).
 *
 * <p>Rule: SRS 3.11.5; BR-57, BR-62, BR-63; UC-42.
 */
public interface PackageAdminService {

    /** Every package, inactive ones included, lowest tier first, then by price. */
    List<AdminPackageResponse> list();

    /** One package. */
    AdminPackageResponse detail(UUID packageId);

    /**
     * Creates a paid package with the limits its tier has now; audited.
     *
     * @throws com.cryptopilot.common.exception.BusinessException PACKAGE_CODE_TAKEN; FREE_PACKAGE_FIXED for tier FREE
     */
    AdminPackageResponse create(UUID adminId, CreatePackageRequest request);

    /**
     * Changes the name, and for a paid package the price, duration and sale flag; audited when something changed.
     *
     * @throws com.cryptopilot.common.exception.BusinessException FREE_PACKAGE_FIXED when an offer field is sent for FREE
     */
    AdminPackageResponse update(UUID adminId, UUID packageId, UpdatePackageRequest request);

    /**
     * Puts the package on offer or takes it off; audited when it changed.
     *
     * @throws com.cryptopilot.common.exception.BusinessException FREE_PACKAGE_FIXED when FREE would be deactivated
     */
    AdminPackageResponse changeActive(UUID adminId, UUID packageId, boolean active);

    /**
     * Deletes a package no order was placed for; audited.
     *
     * @throws com.cryptopilot.common.exception.BusinessException FREE_PACKAGE_FIXED, PACKAGE_IN_USE
     */
    void delete(UUID adminId, UUID packageId);

    /**
     * Saves the limits of a tier to every package of that tier; audited once for each package that changed. The
     * Traders' entitlements are read from the packages on each request, so the change applies at once.
     *
     * @return the packages of the tier afterwards
     */
    List<AdminPackageResponse> updateTierLimits(UUID adminId, PlanTier tier, TierLimitsRequest request);
}
