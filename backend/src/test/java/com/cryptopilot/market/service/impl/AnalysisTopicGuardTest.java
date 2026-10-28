package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The analysis topics need a Trader; Futures needs {@code FUTURES_ANALYSIS} and the components topic
 * {@code SCORE_COMPONENTS}, read from the plan; any other destination under the prefix is refused.
 *
 * <p>Rule: BR-62; SRS CR-08; TECHNICAL_DESIGN 9; D-60, D-126.
 */
class AnalysisTopicGuardTest {

    private static final UUID USER = UUID.randomUUID();
    private static final String SPOT = "/topic/analysis.SPOT.BTCUSDT.1h";
    private static final String FUTURES = "/topic/analysis.FUTURES.BTCUSDT.4h";

    private final EntitlementApi entitlements = mock(EntitlementApi.class);
    private final AnalysisTopicGuard guard = new AnalysisTopicGuard(entitlements);

    @Test
    void D126_governsTheAnalysisPrefixOnly() {
        assertThat(guard.governs(SPOT)).isTrue();
        assertThat(guard.governs("/topic/analysis.anything")).isTrue();
        assertThat(guard.governs("/topic/kline.SPOT.BTCUSDT.1h")).isFalse();
        assertThat(guard.governs("/user/queue/positions")).isFalse();
    }

    @Test
    void BR62_aTraderOnAnyPlan_subscribesToTheSpotTopic() {
        assertThatNoException().isThrownBy(() -> guard.requireAllowed(SPOT, caller("TRADER")));
        verifyNoInteractions(entitlements);
    }

    @Test
    void D126_anAnonymousSession_isRefused() {
        assertThatThrownBy(() -> guard.requireAllowed(SPOT, null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("sign in as a Trader");
    }

    @Test
    void D126_anAdministrator_isRefused() {
        assertThatThrownBy(() -> guard.requireAllowed(SPOT, caller("ADMIN")))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("sign in as a Trader");
    }

    @Test
    void D126_aPrincipalWithoutAuthorities_isRefused() {
        Principal bare = USER::toString;

        assertThatThrownBy(() -> guard.requireAllowed(SPOT, bare)).isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void BR62_futures_withoutFuturesAnalysis_isRefused() {
        assertThatThrownBy(() -> guard.requireAllowed(FUTURES, caller("TRADER")))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("FUTURES_ANALYSIS");
    }

    @Test
    void BR62_futures_withFuturesAnalysis_isAdmitted() {
        when(entitlements.hasFeature(USER, Feature.FUTURES_ANALYSIS)).thenReturn(true);

        assertThatNoException().isThrownBy(() -> guard.requireAllowed(FUTURES, caller("TRADER")));
    }

    @Test
    void BR62_components_withoutScoreComponents_isRefused() {
        assertThatThrownBy(() -> guard.requireAllowed(SPOT + ".components", caller("TRADER")))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("SCORE_COMPONENTS");
    }

    @Test
    void BR62_futuresComponents_needBothFeatures() {
        when(entitlements.hasFeature(USER, Feature.FUTURES_ANALYSIS)).thenReturn(true);

        assertThatThrownBy(() -> guard.requireAllowed(FUTURES + ".components", caller("TRADER")))
                .hasMessageContaining("SCORE_COMPONENTS");

        when(entitlements.hasFeature(USER, Feature.SCORE_COMPONENTS)).thenReturn(true);
        assertThatNoException().isThrownBy(() -> guard.requireAllowed(FUTURES + ".components", caller("TRADER")));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/topic/analysis.SPOT.BTCUSDT.5m",
                "/topic/analysis.spot.BTCUSDT.1h",
                "/topic/analysis.SPOT.BTCUSDT",
                "/topic/analysis.SPOT.BTCUSDT.1h.score",
                "/topic/analysis.SPOT.BTC.USDT.1h",
                "/topic/analysis.MARGIN.BTCUSDT.1h",
                "/topic/analysis.SPOT.*.1h",
                "/topic/analysis.SPOT.BTCUSDT.*",
                "/topic/analysis.**",
                "/topic/analysis.SPOT.BTCUSDT.1h.components.x",
                "/topic/analysis.SPOT.BTCUSDT.1h.COMPONENTS"
            })
    void D126_anotherDestinationUnderThePrefix_isRefused(String destination) {
        assertThatThrownBy(() -> guard.requireAllowed(destination, caller("TRADER")))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("not an analysis topic");
    }

    private static UsernamePasswordAuthenticationToken caller(String role) {
        return UsernamePasswordAuthenticationToken.authenticated(
                USER.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
