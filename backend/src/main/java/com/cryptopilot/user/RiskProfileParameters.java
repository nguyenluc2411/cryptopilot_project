package com.cryptopilot.user;

import com.cryptopilot.user.model.enums.RiskProfile;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The money-management values of one risk profile, as configured (BR-66). Percentages are of the Trader's capital.
 *
 * <p>Rule: BR-66, BR-29; D-53.
 *
 * @param profile the profile these values belong to
 * @param riskPerTradePercent the capital a single plan may risk, in percent
 * @param maxFuturesLeverage the highest leverage before a plan is warned about
 * @param maxTotalOpenRiskPercent the capital all ACTIVE plans and open positions together may risk, in percent
 * @param minSetupScoreToAlert kept in configuration; no feature reads it in this release
 */
public record RiskProfileParameters(
        RiskProfile profile,
        BigDecimal riskPerTradePercent,
        int maxFuturesLeverage,
        BigDecimal maxTotalOpenRiskPercent,
        int minSetupScoreToAlert) {

    public RiskProfileParameters {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(riskPerTradePercent, "riskPerTradePercent");
        Objects.requireNonNull(maxTotalOpenRiskPercent, "maxTotalOpenRiskPercent");
    }
}
