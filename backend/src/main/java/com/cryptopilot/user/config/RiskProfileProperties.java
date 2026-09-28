package com.cryptopilot.user.config;

import com.cryptopilot.user.RiskProfile;
import com.cryptopilot.user.RiskProfileParameters;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The parameters of the three risk profiles, under {@code cryptopilot.user.risk-profiles}. Every profile must be
 * declared and valid, or the application does not start.
 *
 * <p>Checked here: each value is present and positive; the risk per trade lies in the 0.1–10 % that a plan accepts
 * (BR-30); the minimum setup score lies in 0–100; and no profile exceeds AGGRESSIVE on any value, which is the ceiling
 * of BR-66.
 *
 * <p>Rule: BR-66, BR-30; D-53 (rule 6: risk parameters live in configuration); Q-24.
 *
 * @param profiles each profile's parameters
 */
@ConfigurationProperties("cryptopilot.user.risk-profiles")
public record RiskProfileProperties(Map<RiskProfile, Profile> profiles) {

    private static final BigDecimal MIN_RISK_PER_TRADE = new BigDecimal("0.1");
    private static final BigDecimal MAX_RISK_PER_TRADE = BigDecimal.TEN;

    /**
     * One profile as declared.
     *
     * @param riskPerTradePercent percent of capital a single plan may risk
     * @param maxFuturesLeverage highest leverage before a warning
     * @param maxTotalOpenRiskPercent percent of capital all open risk together may reach
     * @param minSetupScoreToAlert 0–100, unused in this release
     */
    public record Profile(
            BigDecimal riskPerTradePercent,
            Integer maxFuturesLeverage,
            BigDecimal maxTotalOpenRiskPercent,
            Integer minSetupScoreToAlert) {}

    public RiskProfileProperties {
        Objects.requireNonNull(profiles, "cryptopilot.user.risk-profiles.profiles must be declared");
        EnumSet<RiskProfile> missing = EnumSet.allOf(RiskProfile.class);
        missing.removeAll(profiles.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("risk profiles not declared: " + missing);
        }
        Map<RiskProfile, RiskProfileParameters> checked = new EnumMap<>(RiskProfile.class);
        profiles.forEach((profile, declared) -> checked.put(profile, toParameters(profile, declared)));
        RiskProfileParameters ceiling = checked.get(RiskProfile.AGGRESSIVE);
        checked.values().forEach(parameters -> requireWithin(parameters, ceiling));
        profiles = Map.copyOf(new EnumMap<>(profiles));
    }

    /** The validated parameters of {@code profile}. */
    public RiskProfileParameters parameters(RiskProfile profile) {
        return toParameters(profile, profiles.get(profile));
    }

    private static RiskProfileParameters toParameters(RiskProfile profile, Profile declared) {
        String name = "risk profile " + profile;
        Objects.requireNonNull(declared, name + " is declared empty");
        BigDecimal risk = Objects.requireNonNull(declared.riskPerTradePercent(), name + ": risk-per-trade-percent");
        Integer leverage = Objects.requireNonNull(declared.maxFuturesLeverage(), name + ": max-futures-leverage");
        BigDecimal openRisk =
                Objects.requireNonNull(declared.maxTotalOpenRiskPercent(), name + ": max-total-open-risk-percent");
        Integer minScore = Objects.requireNonNull(declared.minSetupScoreToAlert(), name + ": min-setup-score-to-alert");
        if (risk.compareTo(MIN_RISK_PER_TRADE) < 0 || risk.compareTo(MAX_RISK_PER_TRADE) > 0) {
            throw new IllegalArgumentException(name + ": risk per trade must be 0.1–10 %, was " + risk);
        }
        if (leverage < 1) {
            throw new IllegalArgumentException(name + ": maximum leverage must be at least 1, was " + leverage);
        }
        if (openRisk.compareTo(risk) < 0) {
            throw new IllegalArgumentException(
                    name + ": maximum total open risk " + openRisk + " is below the risk per trade " + risk);
        }
        if (minScore < 0 || minScore > 100) {
            throw new IllegalArgumentException(name + ": minimum setup score must be 0–100, was " + minScore);
        }
        return new RiskProfileParameters(profile, risk, leverage, openRisk, minScore);
    }

    private static void requireWithin(RiskProfileParameters parameters, RiskProfileParameters ceiling) {
        if (parameters.riskPerTradePercent().compareTo(ceiling.riskPerTradePercent()) > 0
                || parameters.maxFuturesLeverage() > ceiling.maxFuturesLeverage()
                || parameters.maxTotalOpenRiskPercent().compareTo(ceiling.maxTotalOpenRiskPercent()) > 0) {
            throw new IllegalArgumentException(
                    "risk profile " + parameters.profile() + " exceeds the AGGRESSIVE values (BR-66)");
        }
    }
}
