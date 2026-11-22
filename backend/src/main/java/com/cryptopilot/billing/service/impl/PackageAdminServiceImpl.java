package com.cryptopilot.billing.service.impl;

import com.cryptopilot.admin.AdminApi;
import com.cryptopilot.admin.AuditEntry;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import com.cryptopilot.billing.PlanEntitlements;
import com.cryptopilot.billing.dto.request.CreatePackageRequest;
import com.cryptopilot.billing.dto.request.TierLimitsRequest;
import com.cryptopilot.billing.dto.request.UpdatePackageRequest;
import com.cryptopilot.billing.dto.response.AdminPackageResponse;
import com.cryptopilot.billing.entity.SubscriptionPackage;
import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.billing.repository.SubscriptionOrderRepository;
import com.cryptopilot.billing.repository.SubscriptionPackageRepository;
import com.cryptopilot.billing.service.PackageAdminService;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The administrator's package console (SCR-39, UC-42). Each change locks the rows it writes, so two administrators edit
 * one after the other, and is audited in the same transaction (BR-57). Limits belong to the tier (BR-62): a new package
 * takes the limits of its tier, and a limit change is saved to every package of the tier, so the packages of one tier
 * never disagree.
 *
 * <p>Rule: SRS 3.11.5; BR-57, BR-62, BR-63; UC-42.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PackageAdminServiceImpl implements PackageAdminService {

    private static final Comparator<SubscriptionPackage> CONSOLE_ORDER = Comparator.comparingInt(
                    (SubscriptionPackage p) -> p.getTierRank())
            .thenComparing(SubscriptionPackage::getPriceAmount)
            .thenComparing(SubscriptionPackage::getPackageCode);

    /** The unique code of {@code subscription_package} (V1). */
    static final String CODE_CONSTRAINT = "uq_subscription_package_code";

    private final SubscriptionPackageRepository packages;
    private final SubscriptionOrderRepository orders;
    private final AdminApi admin;

    @Override
    @Transactional(readOnly = true)
    public List<AdminPackageResponse> list() {
        Set<UUID> ordered = orders.findOrderedPackageIds();
        return packages.findAll().stream()
                .sorted(CONSOLE_ORDER)
                .map(p -> response(p, ordered.contains(p.getId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AdminPackageResponse detail(UUID packageId) {
        return response(packages.findById(packageId).orElseThrow(() -> notFound(packageId)));
    }

    @Override
    @Transactional
    public AdminPackageResponse create(UUID adminId, CreatePackageRequest request) {
        if (request.tier() == PlanTier.FREE) {
            throw new BusinessException(
                    ErrorCode.FREE_PACKAGE_FIXED, "there is exactly one FREE package; a new package is PRO or PREMIUM");
        }
        if (packages.existsByPackageCode(request.code())) {
            throw new BusinessException(ErrorCode.PACKAGE_CODE_TAKEN, request.code() + " is already a package");
        }
        SubscriptionPackage created = SubscriptionPackage.paid(
                request.code(),
                request.name().strip(),
                request.tier(),
                BigDecimal.valueOf(request.price()),
                request.durationDays(),
                limitsOf(request.tier()));
        created.changeOffer(created.getPriceAmount(), request.durationDays(), request.purchasable());
        SubscriptionPackage saved;
        try {
            saved = packages.saveAndFlush(created);
        } catch (DataIntegrityViolationException raced) {
            // Another administrator took the code between the check above and this insert.
            if (String.valueOf(raced.getMostSpecificCause().getMessage()).contains(CODE_CONSTRAINT)) {
                throw new BusinessException(ErrorCode.PACKAGE_CODE_TAKEN, request.code() + " is already a package");
            }
            throw raced;
        }
        admin.audit(new AuditEntry(
                adminId,
                AuditAction.CONFIGURATION_CREATED,
                AuditedEntity.SUBSCRIPTION_PACKAGE,
                saved.getId(),
                null,
                values(saved)));
        return response(saved);
    }

    @Override
    @Transactional
    public AdminPackageResponse update(UUID adminId, UUID packageId, UpdatePackageRequest request) {
        SubscriptionPackage target = locked(packageId);
        Map<String, Object> before = values(target);
        boolean offerSent = request.price() != null || request.durationDays() != null || request.purchasable() != null;
        if (target.isFree()) {
            if (offerSent) {
                throw new BusinessException(
                        ErrorCode.FREE_PACKAGE_FIXED, "the FREE package's price, duration and sale flag are fixed");
            }
        } else {
            Map<String, String> missing = new LinkedHashMap<>();
            if (request.price() == null) {
                missing.put("price", "MSG01");
            }
            if (request.durationDays() == null) {
                missing.put("durationDays", "MSG01");
            }
            if (request.purchasable() == null) {
                missing.put("purchasable", "MSG01");
            }
            if (!missing.isEmpty()) {
                throw new FieldValidationException("a paid package needs its price, duration and sale flag", missing);
            }
            target.changeOffer(BigDecimal.valueOf(request.price()), request.durationDays(), request.purchasable());
        }
        target.rename(request.name().strip());
        auditChange(adminId, target, before);
        return response(target);
    }

    @Override
    @Transactional
    public AdminPackageResponse changeActive(UUID adminId, UUID packageId, boolean active) {
        SubscriptionPackage target = locked(packageId);
        if (target.isFree() && !active) {
            throw new BusinessException(ErrorCode.FREE_PACKAGE_FIXED, "the FREE package cannot be deactivated");
        }
        Map<String, Object> before = values(target);
        target.changeActive(active);
        auditChange(adminId, target, before);
        return response(target);
    }

    @Override
    @Transactional
    public void delete(UUID adminId, UUID packageId) {
        SubscriptionPackage target = locked(packageId);
        if (target.isFree()) {
            throw new BusinessException(ErrorCode.FREE_PACKAGE_FIXED, "the FREE package cannot be deleted");
        }
        if (orders.existsByPackageId(packageId)) {
            throw new BusinessException(
                    ErrorCode.PACKAGE_IN_USE,
                    target.getPackageCode() + " has orders; deactivate it instead of deleting it");
        }
        Map<String, Object> before = values(target);
        packages.delete(target);
        admin.audit(new AuditEntry(
                adminId,
                AuditAction.CONFIGURATION_DELETED,
                AuditedEntity.SUBSCRIPTION_PACKAGE,
                packageId,
                before,
                null));
    }

    @Override
    @Transactional
    public List<AdminPackageResponse> updateTierLimits(UUID adminId, PlanTier tier, TierLimitsRequest request) {
        Objects.requireNonNull(tier, "tier must not be null");
        PlanEntitlements limits = new PlanEntitlements(
                request.futuresAnalysis(),
                request.scoreComponents(),
                request.activePlanMax(),
                request.watchlistMax(),
                request.activeAlertMax(),
                request.indicatorAlert(),
                request.externalAlertChannels(),
                request.advancedPerformance(),
                request.newsAiInsight(),
                request.aiDailyQuota(),
                request.aiPerformanceContext(),
                request.videoPost());
        List<SubscriptionPackage> ofTier = packages.lockByTier(tier);
        if (ofTier.isEmpty()) {
            throw new ResourceNotFoundException("Subscription packages of tier", tier.name());
        }
        for (SubscriptionPackage target : ofTier) {
            Map<String, Object> before = values(target);
            target.applyLimits(limits);
            auditChange(adminId, target, before);
        }
        return ofTier.stream().sorted(CONSOLE_ORDER).map(this::response).toList();
    }

    /**
     * The limits a new package of this tier starts with: those its tier has now. A tier without packages takes the
     * FREE package's limits, the least a plan grants, until the administrator saves the tier's own.
     */
    private PlanEntitlements limitsOf(PlanTier tier) {
        return packages.findByTier(tier).stream()
                .findFirst()
                .or(() -> packages.findByTier(PlanTier.FREE).stream().findFirst())
                .map(SubscriptionPackage::entitlements)
                .orElseThrow(() -> new IllegalStateException("the FREE package is missing (seed V11)"));
    }

    /** Audits the fields that changed, with the code to name the package; nothing when nothing changed. */
    private void auditChange(UUID adminId, SubscriptionPackage target, Map<String, Object> before) {
        Map<String, Object> after = values(target);
        if (before.equals(after)) {
            return;
        }
        Map<String, Object> oldValue = new LinkedHashMap<>();
        Map<String, Object> newValue = new LinkedHashMap<>();
        oldValue.put("code", target.getPackageCode());
        newValue.put("code", target.getPackageCode());
        after.forEach((key, value) -> {
            if (!Objects.equals(before.get(key), value)) {
                oldValue.put(key, before.get(key));
                newValue.put(key, value);
            }
        });
        admin.audit(new AuditEntry(
                adminId,
                AuditAction.CONFIGURATION_CHANGED,
                AuditedEntity.SUBSCRIPTION_PACKAGE,
                target.getId(),
                oldValue,
                newValue));
    }

    private SubscriptionPackage locked(UUID packageId) {
        return packages.lockById(packageId).orElseThrow(() -> notFound(packageId));
    }

    private static ResourceNotFoundException notFound(UUID packageId) {
        return new ResourceNotFoundException("Subscription package", packageId);
    }

    private static Map<String, Object> values(SubscriptionPackage p) {
        PlanEntitlements e = p.entitlements();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", p.getPackageCode());
        values.put("name", p.getPackageName());
        values.put("tier", p.getTier().name());
        values.put("price", p.getPriceAmount().toPlainString());
        values.put("durationDays", p.getDurationDays());
        values.put("purchasable", p.isPurchasable());
        values.put("active", p.isActive());
        values.put("futuresAnalysis", e.futuresAnalysis());
        values.put("scoreComponents", e.scoreComponents());
        values.put("activePlanMax", e.activePlanMax());
        values.put("watchlistMax", e.watchlistMax());
        values.put("activeAlertMax", e.activeAlertMax());
        values.put("indicatorAlert", e.indicatorAlert());
        values.put("externalAlertChannels", e.externalAlertChannels());
        values.put("advancedPerformance", e.advancedPerformance());
        values.put("newsAiInsight", e.newsAiInsight());
        values.put("aiDailyQuota", e.aiChatDaily());
        values.put("aiPerformanceContext", e.aiPerformanceContext());
        values.put("videoPost", e.videoPost());
        return values;
    }

    private AdminPackageResponse response(SubscriptionPackage p) {
        return response(p, orders.existsByPackageId(p.getId()));
    }

    private AdminPackageResponse response(SubscriptionPackage p, boolean hasOrders) {
        PlanEntitlements e = p.entitlements();
        return new AdminPackageResponse(
                p.getId(),
                p.getPackageCode(),
                p.getPackageName(),
                p.getTier(),
                p.getTierRank(),
                p.getPriceAmount(),
                p.getCurrency(),
                p.getDurationDays(),
                p.isPurchasable(),
                p.isActive(),
                hasOrders,
                new AdminPackageResponse.Limits(
                        e.futuresAnalysis(),
                        e.scoreComponents(),
                        e.activePlanMax(),
                        e.watchlistMax(),
                        e.activeAlertMax(),
                        e.indicatorAlert(),
                        e.externalAlertChannels(),
                        e.advancedPerformance(),
                        e.newsAiInsight(),
                        e.aiChatDaily(),
                        e.aiPerformanceContext(),
                        e.videoPost()),
                p.getUpdatedAt());
    }
}
