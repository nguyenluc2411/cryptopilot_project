package com.cryptopilot.user;

import java.util.UUID;

/**
 * What the {@code user} module tells other modules about a Trader's risk profile: the profile the Trader chose and
 * its configured parameters. The trading plan (T-039) reads it and passes the values to the risk calculation; this
 * module never calls the trading module.
 *
 * <p>Rule: BR-66, BR-29; D-53; Q-24.
 */
public interface RiskProfileApi {

    /**
     * The risk profile of the account and its parameters. An account without a profile row reads the default,
     * CONSERVATIVE, as a Trader who has not chosen would (D-64).
     */
    RiskProfileParameters parametersOf(UUID userId);
}
