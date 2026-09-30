package com.cryptopilot.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.model.enums.SetupStyle;
import com.cryptopilot.user.entity.TradingStyle;
import com.cryptopilot.user.entity.UserProfile;
import com.cryptopilot.user.repository.UserProfileRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The preset each trading style of a profile selects, and the two cases where the profile selects none.
 *
 * <p>Rule: BR-13; SRS 3.2.5; D-53.
 */
class ProfileSetupStyleSourceTest {

    private static final UUID USER = UUID.randomUUID();

    private final UserProfileRepository profiles = mock(UserProfileRepository.class);
    private final ProfileSetupStyleSource source = new ProfileSetupStyleSource(profiles);

    @ParameterizedTest(name = "{0} reads {1}")
    @CsvSource({"SCALPING, SCALPING", "DAY, DAY_TRADING", "SWING, SWING", "POSITION, SWING"})
    void BR13_theProfilesTradingStyle_selectsItsPreset(TradingStyle style, SetupStyle preset) {
        UserProfile profile = UserProfile.createFor(USER, "Trader");
        profile.updateTradingDefaults(null, null, style);
        when(profiles.findById(USER)).thenReturn(Optional.of(profile));

        assertThat(source.styleOf(USER)).contains(preset);
    }

    /** Every trading style maps to a preset, so a style added later cannot reach the score unmapped. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(TradingStyle.class)
    void BR13_everyTradingStyle_hasAPreset(TradingStyle style) {
        assertThat(ProfileSetupStyleSource.presetOf(style)).isNotNull();
    }

    /** A profile that has not chosen a style selects nothing; the analysis then reads DAY_TRADING. */
    @Test
    void BR13_aProfileWithoutATradingStyle_selectsNothing() {
        when(profiles.findById(USER)).thenReturn(Optional.of(UserProfile.createFor(USER, "Trader")));

        assertThat(source.styleOf(USER)).isEmpty();
    }

    @Test
    void BR13_anAccountWithoutAProfile_selectsNothing() {
        when(profiles.findById(USER)).thenReturn(Optional.empty());

        assertThat(source.styleOf(USER)).isEmpty();
    }
}
