package com.cryptopilot.common.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The reverse proxies whose {@code X-Forwarded-For} is believed ({@code TRUSTED_PROXIES}, comma-separated addresses
 * or CIDR ranges). Empty trusts no one: the direct peer is the client. A malformed entry fails start-up, in
 * {@code ClientAddressFilter}.
 *
 * <p>Rule: NSF-18; TECHNICAL_DESIGN 5.3.
 *
 * @param trustedProxies addresses or CIDR ranges; blanks are ignored
 */
@ConfigurationProperties("cryptopilot.web")
public record TrustedProxyProperties(@DefaultValue List<String> trustedProxies) {

    public TrustedProxyProperties {
        trustedProxies = trustedProxies == null
                ? List.of()
                : trustedProxies.stream()
                        .map(String::strip)
                        .filter(entry -> !entry.isEmpty())
                        .toList();
    }
}
