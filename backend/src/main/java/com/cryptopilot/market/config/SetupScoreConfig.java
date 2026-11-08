package com.cryptopilot.market.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

/**
 * Binds the setup score presets (T-027) and the v2 values, so an invalid one stops the application at start-up. The
 * no-room cap must stay below every risk profile's alert threshold; those are read here by property name only, as the
 * user module owns them and market does not depend on it.
 *
 * <p>Rule: BR-13; D-53.
 */
@Configuration
@EnableConfigurationProperties(SetupScoreProperties.class)
public class SetupScoreConfig {

    static final String RISK_PROFILES = "cryptopilot.user.risk-profiles.profiles";
    static final String MIN_SCORE_TO_ALERT = "min-setup-score-to-alert";

    SetupScoreConfig(SetupScoreProperties properties, Environment environment) {
        properties.v2().requireCapBelow(alertThresholds(environment));
    }

    static List<Integer> alertThresholds(Environment environment) {
        Bindable<Map<String, Map<String, String>>> profiles = Bindable.of(ResolvableType.forClassWithGenerics(
                Map.class,
                ResolvableType.forClass(String.class),
                ResolvableType.forClassWithGenerics(Map.class, String.class, String.class)));
        return Binder.get(environment)
                .bind(RISK_PROFILES, profiles)
                .map(Map::values)
                .map(values -> values.stream()
                        .map(profile -> profile.get(MIN_SCORE_TO_ALERT))
                        .filter(Objects::nonNull)
                        .map(Integer::valueOf)
                        .toList())
                .orElse(List.of());
    }
}
