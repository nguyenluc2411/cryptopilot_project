package com.cryptopilot.user.service;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.user.dto.DeviceResponse;
import com.cryptopilot.user.dto.RegisterDeviceRequest;
import com.cryptopilot.user.entity.UserDevice;
import com.cryptopilot.user.repository.UserDeviceRepository;
import java.time.Clock;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The installations an account receives push notifications on: registered by the mobile application
 * after a sign-in, deactivated when it signs out (SRS 3.2.5).
 *
 * <h2>One token, one row</h2>
 *
 * <p>The messaging token is unique across all accounts ({@code uq_user_device_fcm_token}), because it
 * addresses one installation and an installation can only be signed in as one account at a time. A
 * registration is one upsert on that index ({@link UserDeviceRepository#register}): a token nobody
 * holds becomes a new row; a token the caller holds is the same row, active again and seen now; a
 * token another account holds becomes a new device of the caller's — the phone has changed hands, and
 * the previous account keeps no row that would send its notifications to it.
 *
 * <p>One statement rather than a read and then a write, because two registrations of the same token
 * can arrive together — a phone that signs in twice, two accounts on one phone. Read-then-write lets
 * both see nothing and both insert, or both see the old row and both delete it; the upsert lets the
 * index serialise them, and exactly one row holds the token afterwards.
 *
 * <h2>Deactivation belongs to the owner</h2>
 *
 * <p>Both ways of deactivating are keyed by the caller's account as well as by the device, so one
 * account cannot silence another's phone: by key, from the signed-in application, and by token, from
 * a sign-out whose refresh token has already told {@code auth} whose session it is. A device that is
 * not the caller's is answered exactly as one that does not exist.
 *
 * <p>Rule: SRS UC-08, sections 3.2.5 and 4.2.4 (ownership is checked on the server); NSF-15 (push
 * goes to active devices only).
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>.
 * Addison-Wesley ("Service Layer"; "Unit of Work").
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final UserDeviceRepository devices;
    private final Clock clock;

    /**
     * Registers this installation for the caller's account, or brings its row back (SRS 3.2.5).
     *
     * <p>Takes the request record whole, so that the controller never reads the platform, which is an
     * entity type and none of its business.
     */
    @Transactional
    public DeviceResponse register(UUID userId, RegisterDeviceRequest request) {
        UUID deviceId = devices.register(
                UuidV7.next(), userId, request.fcmToken(), request.platform().name(), clock.instant());
        return new DeviceResponse(deviceId, request.platform(), true);
    }

    /**
     * Stops push messages to one of the caller's installations, by its key.
     *
     * <p>Deactivating a device that is already inactive succeeds, because the outcome the caller
     * asked for is the state it is already in.
     *
     * @throws ResourceNotFoundException when no such device exists or it belongs to another account
     */
    @Transactional
    public void deactivate(UUID userId, UUID deviceId) {
        UserDevice device = devices.findById(deviceId)
                .filter(found -> found.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("UserDevice", deviceId));
        device.deactivate();
        devices.save(device);
    }

    /**
     * Stops push messages to the installation holding this token, if it is the caller's; does nothing
     * otherwise. Silent on purpose: it runs inside a sign-out, which answers the same whatever it
     * found.
     */
    @Transactional
    public void deactivateByToken(UUID userId, String fcmToken) {
        devices.findByFcmToken(fcmToken)
                .filter(found -> found.getUserId().equals(userId))
                .ifPresent(device -> {
                    device.deactivate();
                    devices.save(device);
                    log.info("Deactivated device {} of account {} on sign-out", device.getId(), userId);
                });
    }
}
