package com.cryptopilot.user;

import com.cryptopilot.user.model.enums.RiskProfile;
import com.cryptopilot.user.model.enums.TradingStyle;
import java.util.Objects;

/**
 * How a Trader says they trade, as their profile records it (SRS 3.2.5, SCR-07): how long they usually hold a position
 * and how much risk they accept. Preferences only: no capital, no risk percentage, nothing that identifies them, so it
 * may go with a question to the AI Assistant (BR-51).
 *
 * @param tradingStyle how long they hold a position, or {@code null} when the profile has not said
 * @param riskProfile how much risk they accept (BR-66); CONSERVATIVE until they choose (D-64)
 */
public record TradingProfile(TradingStyle tradingStyle, RiskProfile riskProfile) {

    public TradingProfile {
        Objects.requireNonNull(riskProfile, "riskProfile");
    }
}
