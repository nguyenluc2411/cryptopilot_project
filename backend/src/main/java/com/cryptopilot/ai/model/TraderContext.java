package com.cryptopilot.ai.model;

import com.cryptopilot.user.TradingProfile;
import java.util.Objects;

/**
 * How the Trader says they trade, so the answer speaks to it (T-074): a swing trader hears about the daily trend, a
 * scalper about the short timeframes. Preferences only, nothing that identifies the Trader and no capital (BR-51); the
 * answer still recommends no trade.
 *
 * <p>Rule: BR-51, BR-66; SRS 3.2.5.
 *
 * @param tradingStyle {@code SCALPING}, {@code DAY}, {@code SWING} or {@code POSITION}, or {@code null} when the profile
 *     has not said
 * @param riskProfile {@code CONSERVATIVE}, {@code BALANCED} or {@code AGGRESSIVE}
 */
public record TraderContext(String tradingStyle, String riskProfile) {

    public TraderContext {
        Objects.requireNonNull(riskProfile, "riskProfile");
    }

    /** The profile's preferences as the context carries them. */
    public static TraderContext of(TradingProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return new TraderContext(
                profile.tradingStyle() == null ? null : profile.tradingStyle().name(),
                profile.riskProfile().name());
    }
}
