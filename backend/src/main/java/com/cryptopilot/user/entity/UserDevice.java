package com.cryptopilot.user.entity;

import com.cryptopilot.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * A mobile installation that can receive push notifications for one account.
 *
 * <p>Part of the {@link UserAccount} aggregate: {@code fk_user_device_user} cascades, so a device
 * is the account on a phone rather than something the account owns and could outlive it. Unlike
 * {@link UserProfile} it has a key of its own, because one account registers several.
 *
 * <p>The account is held as an identifier, not as an association. Nothing in this task navigates
 * from a device to its account, and the push dispatcher goes the other way — from an account to the
 * devices it has — which a query does better than a mapped collection: the collection would be
 * loaded whole to send to the two devices that are still active.
 *
 * <p>A device is never deleted when somebody signs out. It is deactivated, so that the same
 * installation signing in again is the same row and the token history stays intact; the unique
 * constraint on the token is what makes that identification possible.
 *
 * <p>Rule: SRS UC-08; TECHNICAL_DESIGN section 6.
 *
 * <p>Reference: Vernon, V. (2013). <i>Implementing Domain-Driven Design</i>. Addison-Wesley, ch. 10
 * (reference another aggregate by identity; keep the aggregate small).
 */
@Getter
@Entity
@Table(name = "user_device")
@AttributeOverride(name = "id", column = @Column(name = "device_id", nullable = false, updatable = false))
public class UserDevice extends BaseEntity {

    /** The account this installation belongs to. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** The messaging token a push message is addressed to. Unique across all accounts. */
    @Column(name = "fcm_token", nullable = false, length = 512)
    private String fcmToken;

    /** Which platform the installation runs, which decides how the message is delivered. */
    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 32)
    private DevicePlatform platform;

    /** Whether push messages are still sent to this installation. */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** When the installation was last seen, or {@code null} if it has not been since registering. */
    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    /** For JPA only. */
    protected UserDevice() {}

    private UserDevice(UUID userId, String fcmToken, DevicePlatform platform) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.fcmToken = requireText(fcmToken);
        this.platform = Objects.requireNonNull(platform, "platform must not be null");
    }

    /**
     * Registers an installation for an account. It starts active, which is the default the column
     * declares and which the entity repeats because Hibernate lists every column in an insert.
     *
     * <p>Rule: SRS UC-08.
     */
    public static UserDevice register(UUID userId, String fcmToken, DevicePlatform platform) {
        return new UserDevice(userId, fcmToken, platform);
    }

    /**
     * Stops this installation receiving push messages, on sign-out or when the messaging service
     * reports the token as no longer valid. The row stays, so the same installation returning is
     * recognised rather than duplicated.
     */
    public void deactivate() {
        this.active = false;
    }

    /**
     * Brings a deactivated installation back and records the token the messaging service issued
     * this time, which changes when the application is reinstalled.
     */
    public void reactivateWith(String currentFcmToken) {
        this.fcmToken = requireText(currentFcmToken);
        this.active = true;
    }

    /** Records that the installation was seen, which is how a stale device is later recognised. */
    public void markSeen(Instant at) {
        this.lastSeenAt = Objects.requireNonNull(at, "at must not be null");
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("fcmToken must not be blank");
        }
        return value;
    }
}
