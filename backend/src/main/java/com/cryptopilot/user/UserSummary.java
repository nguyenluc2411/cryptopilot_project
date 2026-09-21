package com.cryptopilot.user;

import java.util.UUID;

/**
 * What another module is told about an account: enough to address it, and nothing else.
 *
 * <p>There is no password hash on it, and deliberately no status either. Not because the status is
 * secret, but because it is a {@code user.entity} type, and a record in the module's public API that
 * exposed it would drag an internal package across the boundary with it — a caller reading
 * {@code accountStatus()} would be depending on {@code user.entity.AccountStatus}.
 *
 * <p>The role was absent for the same reason and is here now, because T-012 is the task that needed
 * it: the access token carries a role claim and a sign-in response tells the client which area to
 * open (SRS 3.2.3). It is published as its own type, {@link UserRole}, exactly as this record said it
 * would be — not as {@code user.entity.Role}, which stays internal.
 *
 * <p>Rule: BR-05; TECHNICAL_DESIGN section 2.
 *
 * @param userId the key of the account
 * @param email the address it signs in with
 * @param role the one role the account holds (BR-05)
 * @param emailVerified whether the address has been verified (BR-01)
 */
public record UserSummary(UUID userId, String email, UserRole role, boolean emailVerified) {}
