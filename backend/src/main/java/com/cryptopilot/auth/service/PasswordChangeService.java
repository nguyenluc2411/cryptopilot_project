package com.cryptopilot.auth.service;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import java.util.UUID;

/**
 * The use cases of {@link com.cryptopilot.auth.service.impl.PasswordChangeServiceImpl}: the methods called from outside it (D-48).
 *
 * <p>Rule: BR-02; SRS UC-07, sections 3.2.5 and 4.2.4; messages MSG03, MSG08.
 */
public interface PasswordChangeService {

    /**
     * Replaces the password of the signed-in account and ends its other sessions.
     *
     * @param userId the account the access token was issued to
     * @param currentSession the session the access token names, or {@code null} when it names none
     * @throws BusinessException {@code PASSWORD_POLICY_VIOLATION} (MSG03) or
     *     {@code CURRENT_PASSWORD_INCORRECT} (MSG08)
     * @throws ResourceNotFoundException when the account behind the token no longer exists
     */
    void changePassword(UUID userId, UUID currentSession, String currentPassword, String newPassword);
}
