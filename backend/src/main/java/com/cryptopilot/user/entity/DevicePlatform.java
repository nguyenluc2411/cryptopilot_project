package com.cryptopilot.user.entity;

/**
 * The mobile platform a registered device runs, which decides how a push notification is addressed.
 *
 * <p>The constants are the values of the {@code ck_user_device_platform} check constraint, spelled
 * the same way. There is no {@code WEB} constant: the web client receives notifications over the
 * realtime connection and registers no device.
 */
public enum DevicePlatform {

    /** Android, addressed through Firebase Cloud Messaging. */
    ANDROID,

    /** iOS, addressed through Firebase Cloud Messaging over APNs. */
    IOS
}
