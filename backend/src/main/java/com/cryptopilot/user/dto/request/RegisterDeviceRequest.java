package com.cryptopilot.user.dto.request;

import com.cryptopilot.user.entity.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What the mobile application sends after a sign-in, to receive push notifications on this
 * installation (SRS 3.2.5).
 *
 * <p>The token is limited to the column's 512 characters. It is not a credential to the API, but it
 * addresses messages to one phone, so it is not printed either.
 *
 * <p>Rule: SRS UC-08, section 3.2.5; message MSG01.
 *
 * @param fcmToken the messaging token the push service issued to this installation
 * @param platform ANDROID or IOS
 */
public record RegisterDeviceRequest(
        @NotBlank(message = "MSG01") @Size(max = 512, message = "MSG01")
        String fcmToken,

        @NotNull(message = "MSG01") DevicePlatform platform) {

    /** Says which platform registered and not which phone. */
    @Override
    public String toString() {
        return "RegisterDeviceRequest(" + platform + ", fcmToken=***)";
    }
}
