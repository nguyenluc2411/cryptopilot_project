package com.cryptopilot.user.model;

import com.cryptopilot.user.model.enums.AccountStatus;
import com.cryptopilot.user.model.enums.Role;
import java.time.Instant;
import java.util.UUID;

/**
 * One account as the user list of SCR-34 reads it, with its profile's name.
 *
 * @param userId the account
 * @param email its address
 * @param displayName the profile's name, or {@code null} without a profile
 * @param role TRADER or ADMIN
 * @param status ACTIVE, LOCKED or BANNED
 * @param emailVerifiedAt when the address was verified, or {@code null}
 * @param lastLoginAt the last sign-in, or {@code null}
 * @param createdAt when the account was registered
 */
public record AccountRow(
        UUID userId,
        String email,
        String displayName,
        Role role,
        AccountStatus status,
        Instant emailVerifiedAt,
        Instant lastLoginAt,
        Instant createdAt) {}
