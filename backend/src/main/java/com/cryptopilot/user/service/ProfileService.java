package com.cryptopilot.user.service;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.user.dto.request.UpdateProfileRequest;
import com.cryptopilot.user.dto.response.ProfileResponse;
import java.util.UUID;

/**
 * The use cases of {@link com.cryptopilot.user.service.impl.ProfileServiceImpl}: the methods called from outside it (D-48).
 *
 * <p>Rule: SRS UC-06, UC-08, sections 3.2.5 and 4.2.4.
 */
public interface ProfileService {

    /**
     * The profile of the signed-in account.
     *
     * @throws ResourceNotFoundException when the account behind the token no longer exists
     */
    ProfileResponse profileOf(UUID userId);

    /**
     * Saves the Profile tab (UC-06). The trading defaults are replaced as a whole, so a field the
     * trader cleared is cleared here too.
     *
     * <p>Answers nothing: SRS 3.2.5 says a successful save shows MSG14, and the tab that needs the
     * saved values reads them back through {@link #profileOf}.
     */
    void updateProfile(UUID userId, UpdateProfileRequest request);

    /** Saves the Notifications tab (UC-08). In-app notifications have no switch and stay on. */
    void updateNotificationPreferences(UUID userId, boolean email, boolean push);
}
