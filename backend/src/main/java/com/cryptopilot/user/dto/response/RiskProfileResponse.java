package com.cryptopilot.user.dto.response;

import com.cryptopilot.user.RiskProfile;
import java.math.BigDecimal;

/**
 * The Trader's risk profile and what it allows (BR-66). The percentages reach the client as strings (TECHNICAL_DESIGN
 * 5.4).
 *
 * @param riskProfile the chosen profile
 * @param riskPerTradePercent percent of capital a single plan may risk
 * @param maxFuturesLeverage highest leverage before a warning
 * @param maxTotalOpenRiskPercent percent of capital all open risk together may reach
 */
public record RiskProfileResponse(
        RiskProfile riskProfile,
        BigDecimal riskPerTradePercent,
        int maxFuturesLeverage,
        BigDecimal maxTotalOpenRiskPercent) {}
