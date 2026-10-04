package com.cryptopilot.auth.service;

/**
 * Retention of authentication tokens (NSF-17).
 *
 * <p>Rule: NSF-17; TECHNICAL_DESIGN 7.15.
 */
public interface TokenRetentionService {

    /**
     * Deletes every refresh token whose own expiry has passed, used or not. A used token that has not expired yet is
     * kept, so that a replay of it still revokes its family (strict reuse detection, no grace period).
     *
     * @return how many tokens were deleted
     */
    int removeExpiredRefreshTokens();
}
