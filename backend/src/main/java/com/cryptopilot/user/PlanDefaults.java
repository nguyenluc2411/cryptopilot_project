package com.cryptopilot.user;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What a new trading plan starts from: the Trader's risk profile and the default capital and risk % of the profile
 * screen. Either default may be unset.
 *
 * <p>Rule: SRS 3.2.5 (default capital and risk % pre-fill new plans; the risk % starts as the profile's risk per
 * trade); BR-66.
 *
 * @param riskProfile the risk profile and its parameters
 * @param defaultCapital the default capital, or {@code null}
 * @param defaultRiskPercent the default risk %, or {@code null}
 */
public record PlanDefaults(
        RiskProfileParameters riskProfile, BigDecimal defaultCapital, BigDecimal defaultRiskPercent) {

    public PlanDefaults {
        Objects.requireNonNull(riskProfile, "riskProfile");
    }
}
