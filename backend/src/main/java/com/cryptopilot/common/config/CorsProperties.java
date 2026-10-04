package com.cryptopilot.common.config;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The browser origins allowed to call the REST API and to open the STOMP endpoint ({@code CORS_ALLOWED_ORIGINS},
 * comma-separated). One list for both, so the two can never disagree. Empty allows no cross-origin caller at all.
 *
 * <p>Each entry is a scheme, a host and an optional port, with no path. A wildcard is accepted only as the port of
 * {@code localhost} or {@code 127.0.0.1} (local development); a bare {@code *} or a wildcard host fails start-up.
 *
 * <p>Rule: TECHNICAL_DESIGN 5.3 (CORS restricted to the configured web origins).
 *
 * @param allowedOrigins the origins, e.g. {@code https://app.example.com}; blanks are ignored
 */
@ConfigurationProperties("cryptopilot.web.cors")
public record CorsProperties(@DefaultValue List<String> allowedOrigins) {

    private static final Pattern ORIGIN = Pattern.compile("https?://[A-Za-z0-9.-]+(:\\d{1,5})?");
    private static final Pattern LOCAL_ANY_PORT = Pattern.compile("http://(localhost|127\\.0\\.0\\.1):\\*");

    public CorsProperties {
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream()
                        .map(String::strip)
                        .filter(origin -> !origin.isEmpty())
                        .toList();
        for (String origin : allowedOrigins) {
            if (!ORIGIN.matcher(origin).matches()
                    && !LOCAL_ANY_PORT.matcher(origin).matches()) {
                throw new IllegalArgumentException(
                        "not an allowed CORS origin (scheme://host[:port], wildcard port on localhost only): "
                                + origin);
            }
        }
    }
}
