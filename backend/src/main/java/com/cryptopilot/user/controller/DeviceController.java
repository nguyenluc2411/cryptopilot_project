package com.cryptopilot.user.controller;

import com.cryptopilot.user.dto.request.RegisterDeviceRequest;
import com.cryptopilot.user.dto.response.DeviceResponse;
import com.cryptopilot.user.service.DeviceService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Push notification devices of the signed-in account (SRS 3.2.5: "The mobile application registers
 * the FCM device token after login and deactivates it on logout").
 *
 * <p>Registration answers 201 with the device key, which the application keeps. Deactivation is by
 * that key rather than by the token, so the token appears in a request body once and never in a URL,
 * where every access log on the way would record it. A sign-out can also deactivate the device by
 * token, through {@code auth}, for an application whose access token has already expired.
 *
 * <p>Rule: SRS UC-08, sections 3.2.5 and 4.2.4; message MSG01; TECHNICAL_DESIGN section 8.
 */
@RestController
@RequestMapping("/api/v1/me/devices")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class DeviceController {

    private final DeviceService devices;

    /** Registers this installation for push notifications, or brings its row back. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceResponse register(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody RegisterDeviceRequest request) {
        return devices.register(ProfileController.accountOf(caller), request);
    }

    /**
     * Stops push notifications to one of the caller's installations. 204 whether it was active or
     * already inactive; 404 when the key names no device of the caller's.
     */
    @DeleteMapping("/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@AuthenticationPrincipal Jwt caller, @PathVariable UUID deviceId) {
        devices.deactivate(ProfileController.accountOf(caller), deviceId);
    }
}
