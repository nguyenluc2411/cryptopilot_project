package com.cryptopilot.user.service.impl;

import com.cryptopilot.admin.AdminApi;
import com.cryptopilot.admin.AuditEntry;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.user.AccountActivityCounter;
import com.cryptopilot.user.dto.response.AdminUserDetailResponse;
import com.cryptopilot.user.dto.response.AdminUserSummaryResponse;
import com.cryptopilot.user.entity.UserAccount;
import com.cryptopilot.user.entity.UserProfile;
import com.cryptopilot.user.event.AccountSuspended;
import com.cryptopilot.user.model.AccountRow;
import com.cryptopilot.user.model.enums.AccountStatus;
import com.cryptopilot.user.model.enums.Role;
import com.cryptopilot.user.repository.AccountSearchRepository;
import com.cryptopilot.user.repository.UserAccountRepository;
import com.cryptopilot.user.repository.UserProfileRepository;
import com.cryptopilot.user.service.AccountAdminService;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SCR-34 and SCR-35. An action loads the account row-locked, checks BR-58 against the caller and the other ACTIVE
 * administrators, applies the transition of {@link UserAccount} (which refuses an illegal one with MSG39), audits it
 * with the reason, and ends the account's sessions through {@link AccountSuspended} when it locks, bans or changes
 * the role: {@code auth} revokes them in the same transaction.
 *
 * <p>Two administrators demoting each other at once could each count the other as the remaining one; the row lock is on
 * the target only. The race needs two administrators acting within the same instant on each other, and is accepted.
 *
 * <p>Rule: SRS 3.11.2, UC-42; BR-05, BR-06, BR-57, BR-58.
 */
@Service
@RequiredArgsConstructor
public class AccountAdminServiceImpl implements AccountAdminService {

    /** The audit entries SCR-35 shows. */
    static final int RECENT_AUDIT = 20;

    private final UserAccountRepository accounts;
    private final UserProfileRepository profiles;
    private final AccountSearchRepository search;
    private final EntitlementApi entitlements;
    private final List<AccountActivityCounter> counters;
    private final AdminApi admin;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminUserSummaryResponse> search(
            String query, Role role, AccountStatus status, PlanTier plan, Integer page, Integer pageSize) {
        PageRequest request = Paging.of(page, pageSize);
        String text = query == null || query.isBlank() ? null : query.strip();
        Set<UUID> only = null;
        Set<UUID> except = Set.of();
        if (plan == PlanTier.FREE) {
            except = new HashSet<>(entitlements.holdersOf(PlanTier.PRO));
            except.addAll(entitlements.holdersOf(PlanTier.PREMIUM));
        } else if (plan != null) {
            only = entitlements.holdersOf(plan);
        }
        AccountSearchRepository.Page found = search.search(text, role, status, only, except, request);
        Map<UUID, PlanTier> tiers = entitlements.paidTiers(
                found.rows().stream().map(AccountRow::userId).toList());
        List<AdminUserSummaryResponse> items = found.rows().stream()
                .map(row -> summary(row, tiers.getOrDefault(row.userId(), PlanTier.FREE)))
                .toList();
        return new PageResponse<>(items, request.getPageNumber() + 1, request.getPageSize(), found.total());
    }

    @Override
    @Transactional(readOnly = true)
    public AdminUserDetailResponse get(UUID userId) {
        return detail(account(accounts.findById(userId), userId));
    }

    @Override
    @Transactional
    public AdminUserDetailResponse lock(UUID adminId, UUID userId, String reason) {
        return act(adminId, userId, reason, AuditAction.ACCOUNT_LOCKED, true, UserAccount::lock);
    }

    @Override
    @Transactional
    public AdminUserDetailResponse unlock(UUID adminId, UUID userId, String reason) {
        return act(adminId, userId, reason, AuditAction.ACCOUNT_UNLOCKED, false, UserAccount::unlock);
    }

    @Override
    @Transactional
    public AdminUserDetailResponse ban(UUID adminId, UUID userId, String reason) {
        return act(adminId, userId, reason, AuditAction.ACCOUNT_BANNED, true, UserAccount::ban);
    }

    @Override
    @Transactional
    public AdminUserDetailResponse changeRole(UUID adminId, UUID userId, Role role, String reason) {
        UserAccount account = account(accounts.findByIdForUpdate(userId), userId);
        if (account.getRole() == role) {
            return detail(account);
        }
        requireNotSelf(adminId, userId, "change the role of");
        if (account.getRole() == Role.ADMIN) {
            requireAnotherActiveAdmin(account, "demote");
        }
        Map<String, Object> before = values(account);
        account.changeRole(role);
        return saved(adminId, account, reason, AuditAction.ROLE_CHANGED, before, true);
    }

    private AdminUserDetailResponse act(
            UUID adminId,
            UUID userId,
            String reason,
            AuditAction action,
            boolean endsSessions,
            Consumer<UserAccount> transition) {
        UserAccount account = account(accounts.findByIdForUpdate(userId), userId);
        if (endsSessions) {
            requireNotSelf(adminId, userId, "lock or ban");
            if (account.getRole() == Role.ADMIN) {
                requireAnotherActiveAdmin(account, "lock or ban");
            }
        }
        Map<String, Object> before = values(account);
        transition.accept(account);
        return saved(adminId, account, reason, action, before, endsSessions);
    }

    private AdminUserDetailResponse saved(
            UUID adminId,
            UserAccount account,
            String reason,
            AuditAction action,
            Map<String, Object> before,
            boolean endsSessions) {
        accounts.save(account);
        Map<String, Object> after = values(account);
        after.put("reason", reason.strip());
        admin.audit(new AuditEntry(adminId, action, AuditedEntity.USER_ACCOUNT, account.getId(), before, after));
        if (endsSessions) {
            Instant now = clock.instant();
            events.publishEvent(new AccountSuspended(account.getId(), now));
        }
        return detail(account);
    }

    private static void requireNotSelf(UUID adminId, UUID userId, String what) {
        if (adminId.equals(userId)) {
            throw new BusinessException(
                    ErrorCode.ACCOUNT_STATUS_TRANSITION_INVALID,
                    "an administrator cannot " + what + " their own account");
        }
    }

    private void requireAnotherActiveAdmin(UserAccount account, String what) {
        if (account.getAccountStatus() == AccountStatus.ACTIVE && search.countOtherActiveAdmins(account.getId()) == 0) {
            throw new BusinessException(
                    ErrorCode.ACCOUNT_STATUS_TRANSITION_INVALID, "cannot " + what + " the last active administrator");
        }
    }

    private AdminUserDetailResponse detail(UserAccount account) {
        Optional<UserProfile> profile = profiles.findById(account.getId());
        PlanTier plan = entitlements.effectivePlan(account.getId()).tier();
        AccountRow row = new AccountRow(
                account.getId(),
                account.getEmail(),
                profile.map(UserProfile::getDisplayName).orElse(null),
                account.getRole(),
                account.getAccountStatus(),
                account.getEmailVerifiedAt(),
                account.getLastLoginAt(),
                account.getCreatedAt());
        Map<String, Long> activity = new TreeMap<>();
        for (AccountActivityCounter counter : counters) {
            activity.putAll(counter.count(account.getId()));
        }
        return new AdminUserDetailResponse(
                summary(row, plan),
                profile.map(p -> p.getTradingStyle() == null
                                ? null
                                : p.getTradingStyle().name())
                        .orElse(null),
                profile.map(p -> p.getRiskProfile() == null
                                ? null
                                : p.getRiskProfile().name())
                        .orElse(null),
                activity,
                admin.recentEntries(AuditedEntity.USER_ACCOUNT, account.getId(), RECENT_AUDIT));
    }

    private static AdminUserSummaryResponse summary(AccountRow row, PlanTier plan) {
        return new AdminUserSummaryResponse(
                row.userId(),
                row.email(),
                row.displayName(),
                row.role(),
                row.status(),
                plan,
                row.emailVerifiedAt() != null,
                row.lastLoginAt(),
                row.createdAt());
    }

    private static Map<String, Object> values(UserAccount account) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("email", account.getEmail());
        values.put("status", account.getAccountStatus().name());
        values.put("role", account.getRole().name());
        return values;
    }

    private static UserAccount account(Optional<UserAccount> found, UUID userId) {
        return found.orElseThrow(() -> new ResourceNotFoundException("UserAccount", userId));
    }
}
