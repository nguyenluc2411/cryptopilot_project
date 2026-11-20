package com.cryptopilot.user.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What the Edit dialog of a member page saves (D-99, as on CoinMarketCap Community): the introduction, one link, the
 * avatar and the banner.
 *
 * <p>A replacement, not a patch, like the Profile tab: the dialog sends every field, and an empty one clears what was
 * stored. An image is named by the key of a form from {@code /me/profile-images/upload-signature}; the key the profile
 * already has keeps the image as it is, and a new one must have been signed for the caller and be stored as an image
 * within the size limit. The display name stays on the Profile tab (UC-06).
 *
 * <p>Rule: D-99; messages MSG01, MSG32.
 *
 * @param bio up to 300 characters, or {@code null}
 * @param websiteUrl an {@code https} address of up to 200 characters, or {@code null} or blank for none
 * @param avatarPublicId the key of the avatar, or {@code null} for none
 * @param bannerPublicId the key of the banner, or {@code null} for the default one
 */
public record CommunityProfileRequest(
        @Size(max = 300, message = "MSG01") String bio,

        @Size(max = 200, message = "MSG01") @Pattern(regexp = "\\s*|https://\\S+", message = "MSG01")
        String websiteUrl,

        @Size(max = 255, message = "MSG01") String avatarPublicId,

        @Size(max = 255, message = "MSG01") String bannerPublicId) {}
