package com.cryptopilot.user.service;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.user.dto.ProfileResponse;
import com.cryptopilot.user.dto.UpdateProfileRequest;
import com.cryptopilot.user.entity.UserAccount;
import com.cryptopilot.user.entity.UserProfile;
import com.cryptopilot.user.repository.UserAccountRepository;
import com.cryptopilot.user.repository.UserProfileRepository;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>Rule: SRS UC-06, UC-08, sections 3.2.5 and 4.2.4.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>.
 * Addison-Wesley ("Service Layer").
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ProfileService {

    private final UserAccountRepository accounts;
    private final UserProfileRepository profiles;

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
                profile.isNotifyEmail(),
                profile.isNotifyPush());
    }
}
