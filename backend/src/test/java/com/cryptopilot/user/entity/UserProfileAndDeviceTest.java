package com.cryptopilot.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The two parts of the account aggregate that carry state without carrying a rule: what a profile
 * remembers about a person, and whether an installation still receives push messages.
 *
 * <p>There is little to prove here and this class says so rather than inventing checks. The
 * invariants that do exist are that a profile always has a name to show — it appears on every post
 * and comment — and that a device always has a token to address, since a device that cannot be
 * addressed is not a device. The figures a profile remembers are deliberately unchecked: BR-30
 * bounds the capital and risk of a <em>plan</em>, and the plan is what must refuse a figure outside
 * them, so a bound repeated here would be a second place to change when the rule moves.
 *
 * <p>How these fields survive a write and a read is asserted against the real schema in the mapping
 * test; what is here is only the behaviour.
 */
class UserProfileAndDeviceTest {

    private static final UUID ACCOUNT = UUID.fromString("019b76da-a800-7000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-21T10:15:30Z");

    @Test
    void aNewProfile_isKeyedByItsAccountAndStartsWithBothNotificationChannelsOn() {
        UserProfile profile = UserProfile.createFor(ACCOUNT, "Nguyen Van A");

        assertThat(profile.getId())
                .as("the profile has no identity apart from the account it describes")
                .isEqualTo(ACCOUNT);
        assertThat(profile.getDisplayName()).isEqualTo("Nguyen Van A");
        assertThat(profile.isNotifyEmail()).isTrue();
        assertThat(profile.isNotifyPush()).isTrue();
        assertThat(profile.getDefaultCapital()).isNull();
        assertThat(profile.getDefaultRiskPercent()).isNull();
        assertThat(profile.getTradingStyle()).isNull();
        assertThat(profile.getAvatarUrl()).isNull();
    }

    @Test
    void aProfile_canBeRenamedAndCanChangeItsAvatarAndItsDefaults() {
        UserProfile profile = UserProfile.createFor(ACCOUNT, "Before");

        profile.rename("After");
        profile.changeAvatar("https://example.invalid/avatar.png");
        profile.updateTradingDefaults(new BigDecimal("2500.00000000"), new BigDecimal("1.500"), TradingStyle.DAY);
        profile.updateNotificationPreferences(false, false);

        assertThat(profile.getDisplayName()).isEqualTo("After");
        assertThat(profile.getAvatarUrl()).isEqualTo("https://example.invalid/avatar.png");
        assertThat(profile.getDefaultCapital()).isEqualByComparingTo("2500");
        assertThat(profile.getDefaultRiskPercent()).isEqualByComparingTo("1.5");
        assertThat(profile.getTradingStyle()).isEqualTo(TradingStyle.DAY);
        assertThat(profile.isNotifyEmail()).isFalse();
        assertThat(profile.isNotifyPush()).isFalse();
    }

    @Test
    void aProfileWithoutANameToShow_isRefused() {
        assertThatThrownBy(() -> UserProfile.createFor(ACCOUNT, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserProfile.createFor(ACCOUNT, "Valid").rename(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserProfile.createFor(null, "Valid")).isInstanceOf(NullPointerException.class);
    }

    /** Clearing the defaults is a legitimate edit: the person no longer wants the form pre-filled. */
    @Test
    void aProfile_canClearTheDefaultsItOnceRemembered() {
        UserProfile profile = UserProfile.createFor(ACCOUNT, "Nguyen Van A");
        profile.updateTradingDefaults(BigDecimal.TEN, BigDecimal.ONE, TradingStyle.POSITION);

        profile.updateTradingDefaults(null, null, null);

        assertThat(profile.getDefaultCapital()).isNull();
        assertThat(profile.getDefaultRiskPercent()).isNull();
        assertThat(profile.getTradingStyle()).isNull();
    }

    @Test
    void aNewDevice_startsActiveAndBelongsToTheAccountThatRegisteredIt() {
        UserDevice device = UserDevice.register(ACCOUNT, "fcm-token", DevicePlatform.ANDROID);

        assertThat(device.getUserId()).isEqualTo(ACCOUNT);
        assertThat(device.getFcmToken()).isEqualTo("fcm-token");
        assertThat(device.getPlatform()).isEqualTo(DevicePlatform.ANDROID);
        assertThat(device.isActive()).isTrue();
        assertThat(device.getLastSeenAt()).isNull();
    }

    /**
     * Signing out stops the messages without removing the row, so the same installation coming back
     * is recognised rather than registered twice — which the unique constraint on the token would
     * refuse anyway.
     */
    @Test
    void aDevice_isDeactivatedRatherThanForgottenAndComesBackWithItsCurrentToken() {
        UserDevice device = UserDevice.register(ACCOUNT, "fcm-old", DevicePlatform.IOS);

        device.deactivate();
        assertThat(device.isActive()).isFalse();
        assertThat(device.getFcmToken()).isEqualTo("fcm-old");

        device.reactivateWith("fcm-new");
        assertThat(device.isActive()).isTrue();
        assertThat(device.getFcmToken())
                .as("a reinstalled application is issued a new token for the same installation")
                .isEqualTo("fcm-new");
    }

    @Test
    void aDevice_recordsWhenItWasLastSeen() {
        UserDevice device = UserDevice.register(ACCOUNT, "fcm-token", DevicePlatform.ANDROID);

        device.markSeen(NOW);

        assertThat(device.getLastSeenAt()).isEqualTo(NOW);
    }

    @Test
    void aDeviceWithoutAnAccount_aTokenOrAPlatform_isRefused() {
        assertThatThrownBy(() -> UserDevice.register(null, "fcm-token", DevicePlatform.IOS))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> UserDevice.register(ACCOUNT, "  ", DevicePlatform.IOS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserDevice.register(ACCOUNT, "fcm-token", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> UserDevice.register(ACCOUNT, "fcm-token", DevicePlatform.IOS)
                        .reactivateWith(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
