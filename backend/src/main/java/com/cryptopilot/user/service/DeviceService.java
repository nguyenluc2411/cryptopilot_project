package com.cryptopilot.user.service;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.user.dto.request.RegisterDeviceRequest;
import com.cryptopilot.user.dto.response.DeviceResponse;
import java.util.UUID;

/**
 * The use cases of {@link com.cryptopilot.user.service.impl.DeviceServiceImpl}: the methods called from outside it (D-48).
 *
 * <p>Rule: SRS UC-08, sections 3.2.5 and 4.2.4 (ownership is checked on the server); NSF-15 (push goes to active devices only).
 */
public interface DeviceService {

    /**
     * Registers this installation for the caller's account, or brings its row back (SRS 3.2.5).
     *
     * <p>Takes the request record whole, so that the controller never reads the platform, which is an
     * entity type and none of its business.
     */
    DeviceResponse register(UUID userId, RegisterDeviceRequest request);

    /**
     * Stops push messages to one of the caller's installations, by its key.
     *
     * <p>Deactivating a device that is already inactive succeeds, because the outcome the caller
     * asked for is the state it is already in.
     *
     * @throws ResourceNotFoundException when no such device exists or it belongs to another account
     */
    void deactivate(UUID userId, UUID deviceId);

    /**
     * Stops push messages to the installation holding this token, if it is the caller's; does nothing
     * otherwise. Silent on purpose: it runs inside a sign-out, which answers the same whatever it
     * found.
     */
    void deactivateByToken(UUID userId, String fcmToken);
}
