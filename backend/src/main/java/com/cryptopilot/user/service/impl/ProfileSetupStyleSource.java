package com.cryptopilot.user.service.impl;

import com.cryptopilot.market.SetupStyle;
import com.cryptopilot.market.SetupStyleSource;
import com.cryptopilot.user.entity.TradingStyle;
import com.cryptopilot.user.entity.UserProfile;
import com.cryptopilot.user.repository.UserProfileRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The style preset a signed-in Trader's setup score is read under, taken from the {@code trading_style} of their
 * profile: SCALPING reads SCALPING, DAY reads DAY_TRADING, SWING and POSITION read SWING (there is no POSITION preset
 * in v1).
 *
 * <p>Empty when the account has no profile row or the profile has no trading style. The analysis then falls back to
 * DAY_TRADING, and it does the same for a Guest, who has no user id and never reaches this class. That fallback lives
 * in the {@code market} module, not here, so this class only answers what the profile selects.
 *
 * <p>The profile's style is the only source of the preset; there is no second style field. The risk profile is never
 * read, so it cannot change a score.
 *
 * <p>Rule: BR-13; SRS 3.2.5; D-53.
 *
 * <p>Reference: Gamma, E., Helm, R., Johnson, R., &amp; Vlissides, J. (1994). <i>Design Patterns: Elements of
 * Reusable Object-Oriented Software</i>. Addison-Wesley ("Strategy": the profile selects which weighting the score is
 * computed with, and the scoring itself does not change).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ProfileSetupStyleSource implements SetupStyleSource {

    private final UserProfileRepository profiles;

    @Override
    @Transactional(readOnly = true)
    public Optional<SetupStyle> styleOf(UUID userId) {
        return profiles.findById(userId).map(UserProfile::getTradingStyle).map(ProfileSetupStyleSource::presetOf);
    }

    /** The preset of a trading style. Every style has one, so the switch has no default. */
    static SetupStyle presetOf(TradingStyle style) {
        return switch (style) {
            case SCALPING -> SetupStyle.SCALPING;
            case DAY -> SetupStyle.DAY_TRADING;
            case SWING, POSITION -> SetupStyle.SWING;
        };
    }
}
