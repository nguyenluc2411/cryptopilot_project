package com.cryptopilot.user;

import java.util.UUID;

/**
 * What another module is told about an account: enough to address it, and nothing else.
 *
 * <p>There is no password hash on it, and deliberately no role or status either. Not because they
 * are secret, but because they are {@code user.entity} types, and a record in the module's public
 * API that exposed them would drag an internal package across the boundary with it — a caller
 * reading {@code role()} would be depending on {@code user.entity.Role}. When another module needs
 * the role it will be published as its own type, by the task that needs it.
 *
 * <p>Rule: TECHNICAL_DESIGN section 2.
 *
 * @param userId the key of the account
 * @param email the address it signs in with
 * @param emailVerified whether the address has been verified (BR-01)
 */
public record UserSummary(UUID userId, String email, boolean emailVerified) {}
