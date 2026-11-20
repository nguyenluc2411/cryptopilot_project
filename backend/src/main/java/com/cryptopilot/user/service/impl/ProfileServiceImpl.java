package com.cryptopilot.user.service.impl;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.user.client.CloudinaryProfileImageStorage;
import com.cryptopilot.user.client.ProfileImageStorage;
import com.cryptopilot.user.client.ProfileImageStorageException;
import com.cryptopilot.user.client.ProfileImageUploadTicket;
import com.cryptopilot.user.client.StoredProfileImage;
import com.cryptopilot.user.dto.request.CommunityProfileRequest;
import com.cryptopilot.user.dto.request.UpdateProfileRequest;
import com.cryptopilot.user.dto.response.ProfileImageUploadResponse;
import com.cryptopilot.user.dto.response.ProfileResponse;
import com.cryptopilot.user.entity.UserAccount;
import com.cryptopilot.user.entity.UserProfile;
import com.cryptopilot.user.repository.UserAccountRepository;
import com.cryptopilot.user.repository.UserProfileRepository;
import com.cryptopilot.user.service.ProfileService;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The Profile and Notifications tabs of SCR-07: reading the profile of the signed-in account and
 * saving what the two tabs change (SRS UC-06, UC-08).
 *
 * <p>Every method is keyed by the caller's own account, taken from the access token by the
 * controller and never from the request. That is the ownership check of SRS 4.2.4 in its simplest
 * form: there is no path parameter naming a profile, so there is no other profile a request could
 * name.
 *
 * <p>The rules are on {@link UserProfile}: a blank display name is refused by the entity, and the
 * ranges of SRS 3.2.5 are refused by the request record before anything is loaded. What this class
 * decides is only the unit of work — one profile row per request — and what the response contains.
 *
 * <p>The account is read for its address, which SCR-07 shows beside the profile. Two reads by key,
 * rather than an association (D-21).
 *
 * <p>The Edit dialog of the member page (D-99) is saved here too, on the same row: the introduction, the link and
 * the two images. An image is accepted from what the media storage reads back, never from what the client says
 * (OWASP File Upload Cheat Sheet), as the chart images of the journal are.
 *
 * <p>Rule: SRS UC-06, UC-08, sections 3.2.5 and 4.2.4; D-99.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>.
 * Addison-Wesley ("Service Layer").
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ProfileServiceImpl implements ProfileService {

    /** The setting that bounds an uploaded image, the one post and chart images use (BR-44). */
    static final String MAX_IMAGE_SIZE_MB = "MAX_IMAGE_SIZE_MB";

    private static final long MEGABYTE = 1024L * 1024L;

    /** The fields an image error is reported on. */
    static final String AVATAR = "avatarPublicId";

    static final String BANNER = "bannerPublicId";

    private static final Logger log = LoggerFactory.getLogger(ProfileServiceImpl.class);

    private final UserAccountRepository accounts;
    private final UserProfileRepository profiles;
    private final ProfileImageStorage images;
    private final SystemSettingApi settings;

    /**
     * The profile of the signed-in account.
     *
     * @throws ResourceNotFoundException when the account behind the token no longer exists
     */
    @Transactional(readOnly = true)
    public ProfileResponse profileOf(UUID userId) {
        return responseOf(accountOf(userId), profileRowOf(userId));
    }

    /**
     * Saves the Profile tab (UC-06). The trading defaults are replaced as a whole, so a field the
     * trader cleared is cleared here too.
     *
     * <p>Answers nothing: SRS 3.2.5 says a successful save shows MSG14, and the tab that needs the
     * saved values reads them back through {@link #profileOf}.
     */
    @Transactional
    public void updateProfile(UUID userId, UpdateProfileRequest request) {
        UserProfile profile = profileRowOf(userId);
        profile.rename(request.displayName());
        profile.updateTradingDefaults(request.defaultCapital(), request.defaultRiskPercent(), request.tradingStyle());
        profiles.save(profile);
    }

    /** Saves the Notifications tab (UC-08). In-app notifications have no switch and stay on. */
    @Transactional
    public void updateNotificationPreferences(UUID userId, boolean email, boolean push) {
        UserProfile profile = profileRowOf(userId);
        profile.updateNotificationPreferences(email, push);
        profiles.save(profile);
    }

    @Override
    public ProfileImageUploadResponse signProfileImage(UUID userId) {
        ProfileImageUploadTicket ticket = images.sign(userId);
        return new ProfileImageUploadResponse(
                ticket.uploadUrl(),
                ticket.apiKey(),
                ticket.timestamp(),
                ticket.signature(),
                ticket.publicId(),
                ticket.allowedFormats(),
                maxBytes());
    }

    @Override
    @Transactional
    public void updateCommunityProfile(UUID userId, CommunityProfileRequest request) {
        UserProfile profile = profileRowOf(userId);
        String avatarKey = blankToNull(request.avatarPublicId());
        String bannerKey = blankToNull(request.bannerPublicId());
        String oldAvatar = profile.getAvatarPublicId();
        String oldBanner = profile.getBannerPublicId();
        // One stored file cannot be both images: removing either would delete the other.
        if (avatarKey != null && avatarKey.equals(bannerKey)) {
            throw invalidImage(BANNER, "key " + bannerKey + " is already the avatar");
        }
        // Both images are checked before anything changes, so a refused banner leaves a new avatar unsaved too.
        Map<String, String> current = new HashMap<>();
        current.put(oldAvatar, profile.getAvatarUrl());
        current.put(oldBanner, profile.getBannerUrl());
        String avatarUrl = imageUrl(userId, AVATAR, avatarKey, current);
        String bannerUrl = imageUrl(userId, BANNER, bannerKey, current);
        profile.describe(blankToNull(request.bio()), blankToNull(request.websiteUrl()));
        profile.changeAvatar(avatarUrl, avatarKey);
        profile.changeBanner(bannerUrl, bannerKey);
        profiles.save(profile);
        Set<String> kept = new HashSet<>(Arrays.asList(avatarKey, bannerKey));
        destroyAfterCommitUnlessKept(oldAvatar, kept);
        destroyAfterCommitUnlessKept(oldBanner, kept);
    }

    /**
     * Where the image a key names is served from: the stored address for a key the profile already has as avatar or
     * banner, nothing for no key, and for a new key the address the storage itself reads back, once it is known to be
     * the caller's image within the limits.
     */
    private String imageUrl(UUID userId, String field, String key, Map<String, String> current) {
        if (key == null) {
            return null;
        }
        if (current.containsKey(key)) {
            return current.get(key);
        }
        if (!images.issuedTo(userId, key)) {
            throw invalidImage(field, "key " + key + " was not signed for the caller");
        }
        StoredProfileImage stored;
        try {
            stored = images.describe(key).orElseThrow(() -> invalidImage(field, "nothing is stored under " + key));
        } catch (ProfileImageStorageException unavailable) {
            throw new BusinessException(ErrorCode.MEDIA_STORAGE_UNAVAILABLE, unavailable.getMessage());
        }
        if (!CloudinaryProfileImageStorage.FORMATS.contains(stored.format()) || stored.bytes() > maxBytes()) {
            throw invalidImage(field, "image " + key + " is " + stored.format() + " of " + stored.bytes() + " bytes");
        }
        return stored.url();
    }

    private long maxBytes() {
        return MEGABYTE * settings.integer(MAX_IMAGE_SIZE_MB);
    }

    private static FieldValidationException invalidImage(String field, String detail) {
        return new FieldValidationException(ErrorCode.MEDIA_INVALID, detail, Map.of(field, "MSG32"));
    }

    /**
     * Deletes an image the profile no longer uses once the save is committed, unless either new key still names it (a
     * swap of avatar and banner); a failed delete is only logged. The storage is told only what the server checked
     * (OWASP File Upload Cheat Sheet).
     */
    private void destroyAfterCommitUnlessKept(String oldKey, Set<String> kept) {
        if (oldKey == null || kept.contains(oldKey)) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    images.destroy(oldKey);
                } catch (RuntimeException failed) {
                    log.warn(
                            "D-99 profile image {} no longer used could not be deleted: {}", oldKey, failed.toString());
                }
            }
        });
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private UserAccount accountOf(UUID userId) {
        return accounts.findById(userId).orElseThrow(() -> new ResourceNotFoundException("UserAccount", userId));
    }

    private UserProfile profileRowOf(UUID userId) {
        return profiles.findById(userId).orElseThrow(() -> new ResourceNotFoundException("UserProfile", userId));
    }

    private static ProfileResponse responseOf(UserAccount account, UserProfile profile) {
        return new ProfileResponse(
                account.getEmail(),
                profile.getDisplayName(),
                profile.getAvatarUrl(),
                profile.getDefaultCapital(),
                profile.getDefaultRiskPercent(),
                profile.getTradingStyle(),
                profile.getRiskProfile(),
                profile.isNotifyEmail(),
                profile.isNotifyPush(),
                profile.getBio(),
                profile.getWebsiteUrl(),
                profile.getBannerUrl(),
                profile.getAvatarPublicId(),
                profile.getBannerPublicId());
    }
}
