package com.cryptopilot.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Warns once at start-up when production limits requests per client address but trusts no proxy. Behind Nginx every
 * request then comes from the proxy's address, so all clients share one address-based limit.
 *
 * <p>A warning, not a refused start: a deployment reached directly, with no proxy in front, is a valid setup, and
 * failing start-up would break the environments that run that way today.
 *
 * <p>Rule: NSF-18; TECHNICAL_DESIGN 5.3; ADR-014.
 *
 * <p>Reference: Petersson, A. &amp; Nilsson, M. (2014). RFC 7239: Forwarded HTTP Extension, section 5.2. IETF (the
 * forwarded-for value is only as trustworthy as the proxy that added it).
 */
@Component
public class TrustedProxyStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(TrustedProxyStartupCheck.class);

    private final TrustedProxyProperties proxies;
    private final RateLimitProperties limits;
    private final Environment environment;

    public TrustedProxyStartupCheck(
            TrustedProxyProperties proxies, RateLimitProperties limits, Environment environment) {
        this.proxies = proxies;
        this.limits = limits;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnWhenNoProxyIsTrusted() {
        if (limits.enabled() && proxies.trustedProxies().isEmpty() && environment.matchesProfiles("prod")) {
            log.warn(
                    "Rate limiting is on but TRUSTED_PROXIES is empty: behind a reverse proxy every client shares the"
                            + " proxy's address, so the whole system gets {} sign-ins and {} other auth calls per {} s."
                            + " Set TRUSTED_PROXIES to the proxy's address or network.",
                    limits.login(),
                    limits.auth(),
                    limits.window().toSeconds());
        }
    }
}
