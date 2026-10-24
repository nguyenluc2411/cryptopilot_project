package com.cryptopilot.user.service;

import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.user.dto.response.AdminUserDetailResponse;
import com.cryptopilot.user.dto.response.AdminUserSummaryResponse;
import com.cryptopilot.user.model.enums.AccountStatus;
import com.cryptopilot.user.model.enums.Role;
import java.util.UUID;

/**
 * The account administration of SCR-34 and SCR-35 (UC-42). Every action takes a reason and is audited with the old
 * and new values (BR-57); locking, banning and changing a role end every session of the account (BR-06), so the next
 * request signs in again with what changed. No administrator locks, bans or changes the role of their own account,
 * and the last ACTIVE administrator is never locked, banned or demoted (BR-58). Implemented by
 * {@link com.cryptopilot.user.service.impl.AccountAdminServiceImpl}.
 *
 * <p>Rule: SRS 3.11.2, UC-42; BR-05, BR-06, BR-57, BR-58.
 */
public interface AccountAdminService {

    /**
     * One page of accounts, newest first.
     *
     * @param query part of the email or display name, or {@code null}
     * @param plan only accounts on this effective plan, or {@code null}
     * @param page from 1, or {@code null} for 1
     * @param pageSize 1 to 100, or {@code null} for 20
     */
    PageResponse<AdminUserSummaryResponse> search(
            String query, Role role, AccountStatus status, PlanTier plan, Integer page, Integer pageSize);

    /** One account in full; MSG41 when unknown. */
    AdminUserDetailResponse get(UUID userId);

    /** ACTIVE to LOCKED. MSG39 from another status, for the caller's own account, or for the last ACTIVE admin. */
    AdminUserDetailResponse lock(UUID adminId, UUID userId, String reason);

    /** LOCKED to ACTIVE. MSG39 from another status. */
    AdminUserDetailResponse unlock(UUID adminId, UUID userId, String reason);

    /** ACTIVE or LOCKED to BANNED, for good. MSG39 when already banned, for oneself, or for the last ACTIVE admin. */
    AdminUserDetailResponse ban(UUID adminId, UUID userId, String reason);

    /** Assigns the role. MSG39 for oneself, or when it would demote the last ACTIVE admin. */
    AdminUserDetailResponse changeRole(UUID adminId, UUID userId, Role role, String reason);
}
