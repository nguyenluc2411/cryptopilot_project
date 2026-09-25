package com.cryptopilot.user.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.user.dto.request.RegisterDeviceRequest;
import com.cryptopilot.user.entity.DevicePlatform;
import org.junit.jupiter.api.Test;

/**
 * A messaging token addresses one phone, so the registration record says which platform registered
 * and never which phone.
 *
 * <p>Rule: SRS 3.2.5, 4.2.4.
 */
class RegisterDeviceRequestTest {

    @Test
    void UC08_aRegistration_printsThePlatformAndNotTheToken() {
        String printed = new RegisterDeviceRequest("device-secret", DevicePlatform.IOS).toString();

        assertThat(printed).contains("IOS").doesNotContain("device-secret");
    }
}
