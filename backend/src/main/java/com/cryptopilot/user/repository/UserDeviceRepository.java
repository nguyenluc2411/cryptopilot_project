package com.cryptopilot.user.repository;

import com.cryptopilot.user.entity.UserDevice;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
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

    /**
     * Registers a messaging token for an account in one statement, whoever held it before, and answers
     * the key of the row that now holds it (D-34).
     *
     * <p>{@code INSERT ... ON CONFLICT (fcm_token) DO UPDATE}, so the unique index
     * {@code uq_user_device_fcm_token} is what serialises two registrations of the same token: the
     * second waits for the first and then updates the row the first wrote, instead of failing on the
     * index or deleting a row that is already gone. Whatever the interleaving, exactly one row holds the
     * token afterwards, it is active, and it belongs to whichever registration committed last.
     *
     * <p>When the row already belongs to the same account it keeps its key, its creation instant and its
     * history, and is simply active again and seen now. When it belonged to another account it becomes a
     * new device — new key, new creation instant, version zero — because a phone that changed hands is
     * not the previous owner's device, and the old owner no longer has a row that would send their
     * notifications to it. Every right-hand side reads the row as it was before the update, which is why
     * each {@code CASE} can compare the old owner while {@code user_id} is being replaced.
     *
     * <p>Rule: SRS 3.2.5; D-34.
     *
     * <p>Reference: PostgreSQL Global Development Group. <i>PostgreSQL 16 Documentation</i>, "INSERT",
     * section "ON CONFLICT Clause" (an atomic insert-or-update outcome under concurrency).
     */
    @Query(value = """
                    insert into user_device (device_id, user_id, fcm_token, platform, is_active, last_seen_at,
                                             created_at, updated_at, version)
                    values (:deviceId, :userId, :fcmToken, :platform, true, :now, :now, :now, 0)
                    on conflict (fcm_token) do update set
                        device_id  = case when user_device.user_id = excluded.user_id
                                          then user_device.device_id else excluded.device_id end,
                        created_at = case when user_device.user_id = excluded.user_id
                                          then user_device.created_at else excluded.created_at end,
                        version    = case when user_device.user_id = excluded.user_id
                                          then user_device.version + 1 else 0 end,
                        user_id      = excluded.user_id,
                        platform     = excluded.platform,
                        is_active    = true,
                        last_seen_at = excluded.last_seen_at,
                        updated_at   = excluded.updated_at
                    returning device_id""", nativeQuery = true)
    UUID register(
            @Param("deviceId") UUID deviceId,
            @Param("userId") UUID userId,
            @Param("fcmToken") String fcmToken,
            @Param("platform") String platform,
            @Param("now") Instant now);
}
