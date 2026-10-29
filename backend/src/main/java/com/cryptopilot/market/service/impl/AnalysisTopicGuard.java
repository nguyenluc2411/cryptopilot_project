package com.cryptopilot.market.service.impl;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.service.TopicSubscriptionGuard;
import com.cryptopilot.market.model.enums.MarketType;
import java.security.Principal;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Who may subscribe to the analysis topics of {@link AnalysisPushServiceImpl}: a session signed in on {@code CONNECT}
 * as a Trader, whose plan includes {@code FUTURES_ANALYSIS} for a Futures series and {@code SCORE_COMPONENTS} for the
 * {@code .components} topic — the access classes of the analysis over REST (D-60), read from the same
 * {@link EntitlementApi}. Anything else under {@code /topic/analysis.} is refused, so a misspelt destination never
 * falls through to another rule.
 *
 * <p>The plan is read once, when the session subscribes: a plan that drops later keeps the subscription until the
 * client unsubscribes or reconnects (the accepted limit of D-126).
 *
 * <p>Rule: BR-62, NSF-05; SRS 3.1.3, CR-08; TECHNICAL_DESIGN 9; D-60, D-126.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AnalysisTopicGuard implements TopicSubscriptionGuard {

    static final String PREFIX = "/topic/analysis.";

    /** {@code /topic/analysis.{market}.{symbol}.{tf}} with an optional {@code .components}, as the push names them. */
    private static final Pattern TOPIC =
            Pattern.compile("/topic/analysis\\.(SPOT|FUTURES)\\.[A-Z0-9]+\\.(15m|1h|4h|1d)(\\.components)?");

    private static final String TRADER = "ROLE_TRADER";

    private final EntitlementApi entitlements;

    @Override
    public boolean governs(String destination) {
        return destination.startsWith(PREFIX);
    }

    @Override
    public void requireAllowed(String destination, Principal user) {
        Matcher topic = TOPIC.matcher(destination);
        if (!topic.matches()) {
            throw new MessageDeliveryException("not an analysis topic: " + destination);
        }
        if (!(user instanceof Authentication caller) || !isTrader(caller)) {
            throw new MessageDeliveryException("sign in as a Trader to subscribe to " + destination);
        }
        UUID trader = UUID.fromString(caller.getName());
        if (MarketType.FUTURES.name().equals(topic.group(1))
                && !entitlements.hasFeature(trader, Feature.FUTURES_ANALYSIS)) {
            throw new MessageDeliveryException("the plan does not include " + Feature.FUTURES_ANALYSIS);
        }
        if (topic.group(3) != null && !entitlements.hasFeature(trader, Feature.SCORE_COMPONENTS)) {
            throw new MessageDeliveryException("the plan does not include " + Feature.SCORE_COMPONENTS);
        }
    }

    private static boolean isTrader(Authentication caller) {
        return caller.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(TRADER::equals);
    }
}
