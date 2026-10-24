package com.cryptopilot.user.dto.response;

import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.user.model.enums.AccountStatus;
import com.cryptopilot.user.model.enums.Role;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the user list of SCR-34.
 *
 * <p>Rule: SRS 3.11.2; UC-42.
 *
 * @param userId the account
 * @param email its address
 * @param displayName the profile's name, or {@code null}
 * @param role TRADER or ADMIN
 * @param status ACTIVE, LOCKED or BANNED
 * @param plan the effective plan now: FREE, PRO or PREMIUM
 * @param emailVerified whether the address is verified
 * @param lastLoginAt the last sign-in, or {@code null}
 * @param createdAt when the account was registered
 */
public record AdminUserSummaryResponse(
        UUID userId,
        String email,
        String displayName,
        Role role,
        AccountStatus status,
        PlanTier plan,
        boolean emailVerified,
        Instant lastLoginAt,
        Instant createdAt) {}
