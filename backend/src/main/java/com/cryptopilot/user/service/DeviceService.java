package com.cryptopilot.user.service;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.user.dto.DeviceResponse;
import com.cryptopilot.user.dto.RegisterDeviceRequest;
import com.cryptopilot.user.entity.UserDevice;
import com.cryptopilot.user.repository.UserDeviceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
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
 * addresses one installation and an installation can only be signed in as one account at a time. So a
 * registration is decided by who holds the token now:
 *
 * <ul>
 *   <li>nobody — a new row, active;
 *   <li>this account — the same row, active again and seen now, so an installation that signs out and
 *       back in is recognised rather than duplicated, which is what {@link UserDevice#deactivate()}
 *       keeps the row for;
 *   <li>another account — the phone has changed hands, or a second person signed in on it. The old
 *       row is removed and a new one written for the caller. Keeping it and moving it would give the
 *       previous account's device history to somebody else, and leaving it would send the previous
 *       account's notifications to the new person's phone.
 * </ul>
 *
 * <p>The last case deletes and inserts the same token in one transaction, and Hibernate orders
 * inserts before deletes, so the delete is flushed first or the unique index would refuse the
 * insert.
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
        Instant now = clock.instant();
        String fcmToken = request.fcmToken();
        Optional<UserDevice> existing = devices.findByFcmToken(fcmToken);

        UserDevice device;
        if (existing.isPresent() && existing.get().getUserId().equals(userId)) {
            device = existing.get();
            device.reactivateWith(fcmToken);
        } else {
            existing.ifPresent(this::removePreviousOwnersRow);
            device = UserDevice.register(userId, fcmToken, request.platform());
        }
        device.markSeen(now);
        devices.save(device);
        return responseOf(device);
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

    private void removePreviousOwnersRow(UserDevice previous) {
        log.info("Device token moved from account {} to another account", previous.getUserId());
        devices.delete(previous);
        devices.flush();
    }

    private static DeviceResponse responseOf(UserDevice device) {
        return new DeviceResponse(device.getId(), device.getPlatform(), device.isActive());
    }
}
