package com.cryptopilot.user.service;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.user.dto.request.CommunityProfileRequest;
import com.cryptopilot.user.dto.request.UpdateProfileRequest;
import com.cryptopilot.user.dto.response.ProfileImageUploadResponse;
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

    /**
     * A signed form to upload one avatar or banner of the caller's member page (D-99).
     *
     * @throws com.cryptopilot.common.exception.BusinessException {@code MEDIA_STORAGE_UNAVAILABLE} without a storage
     */
    ProfileImageUploadResponse signProfileImage(UUID userId);

    /**
     * Saves what the caller's member page says of them (D-99): introduction, link, avatar and banner, replaced as a
     * whole. A replaced or cleared image is deleted from the storage once the save is committed.
     *
     * @throws com.cryptopilot.common.exception.FieldValidationException MSG32 ({@code MEDIA_INVALID}) for an image key
     *     not signed for the caller, with nothing stored, or not an image within the size limit
     * @throws com.cryptopilot.common.exception.BusinessException {@code MEDIA_STORAGE_UNAVAILABLE} when the storage
     *     cannot be read
     */
    void updateCommunityProfile(UUID userId, CommunityProfileRequest request);
}
