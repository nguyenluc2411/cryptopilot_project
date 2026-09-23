package com.cryptopilot.user.repository;

import com.cryptopilot.user.entity.UserDevice;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to a registered device.
 *
 * <p>The second documented exception to "one repository per aggregate root", for the reason the
 * profile is the first: a device is part of the account aggregate, no association leads to it, so
 * without a gateway of its own a row with its own table and its own key could not be reached at all.
 * The argument is the one {@link UserProfileRepository} states and is not repeated here.
 *
 * <p>Every lookup is by the messaging token or by the device's own key, never "every device of this
 * account": nothing in UC-08 lists devices, and the push dispatcher that will need "the active
 * devices of an account" is a later task that adds the query together with the index it uses.
 *
 * <p>Rule: SRS 3.2.5 (the mobile application registers the device token after login and deactivates
 * it on logout); TECHNICAL_DESIGN sections 3.1 and 6.
 */
public interface UserDeviceRepository extends Repository<UserDevice, UUID> {

    /** The device with this key, or empty. */
    @Transactional(readOnly = true)
    Optional<UserDevice> findById(UUID deviceId);

    /**
     * The device holding this messaging token, whichever account it belongs to, or empty. Backed by
     * {@code uq_user_device_fcm_token}, which is also what makes the answer at most one row.
     */
    @Transactional(readOnly = true)
    Optional<UserDevice> findByFcmToken(String fcmToken);

    /** Writes a device. Not transactional here; the unit of work is the calling service's. */
    UserDevice save(UserDevice device);

    /** Removes a device row. Used only when an installation passes to another account. */
    void delete(UserDevice device);

    /**
     * Sends the pending statements now, so that a delete reaches the database before the insert that
     * reuses its token — Hibernate otherwise orders inserts before deletes and the unique index would
     * refuse the insert.
     */
    void flush();
}
