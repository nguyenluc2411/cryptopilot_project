package com.cryptopilot.news.calculator;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The one form of a web address that two spellings of the same page share, so that an article reached twice, or a
 * feed entered twice, is recognised (BR-48, UC-49). The scheme and host are lower-cased, the default port and the
 * fragment removed, an empty path written as {@code /}, the tracking parameters removed and the remaining query
 * parameters sorted. The path is otherwise kept as it is: a trailing slash or a letter case in it may name another
 * page.
 *
 * <p>Only an absolute {@code http} or {@code https} address with a host is a web address here. One that carries user
 * information ({@code https://name@host}) is refused too: it is never a feed or an article, and it is the usual way of
 * making one host look like another.
 *
 * <p>Rule: BR-48, NSF-10, UC-49; TECHNICAL_DESIGN 7.13.
 *
 * <p>Reference: Berners-Lee, T., Fielding, R. &amp; Masinter, L. (2005). RFC 3986, <i>Uniform Resource Identifier
 * (URI): Generic Syntax</i>, section 6.2 (syntax- and scheme-based normalization).
 */
public final class UrlNormalizer {

    /** Query parameters whose names start with one of these only tell the outlet where the reader came from. */
    private static final List<String> TRACKING_PREFIXES = List.of("utm_");

    /** Query parameters that only identify a click or a campaign, never the page. */
    private static final Set<String> TRACKING_NAMES =
            Set.of("fbclid", "gclid", "dclid", "msclkid", "yclid", "_ga", "_gl", "mc_cid", "mc_eid", "igshid");

    private UrlNormalizer() {}

    /**
     * The normalized form of {@code address}, or empty when it is not an absolute {@code http} or {@code https} address
     * with a host and no user information.
     */
    public static Optional<String> normalize(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        URI uri;
        try {
            // A feed sometimes writes a space into a link; it is the one character an outlet leaves unencoded.
            uri = new URI(address.strip().replace(" ", "%20"));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return Optional.empty();
        }
        if (uri.getHost() == null || uri.getRawUserInfo() != null) {
            return Optional.empty();
        }
        StringBuilder normalized =
                new StringBuilder(scheme).append("://").append(uri.getHost().toLowerCase(Locale.ROOT));
        int port = uri.getPort();
        if (port != -1 && port != defaultPort(scheme)) {
            normalized.append(':').append(port);
        }
        String path = uri.getRawPath();
        normalized.append(path == null || path.isEmpty() ? "/" : path);
        String query = query(uri.getRawQuery());
        if (!query.isEmpty()) {
            normalized.append('?').append(query);
        }
        return Optional.of(normalized.toString());
    }

    private static int defaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }

    /** The parameters that name the page, sorted, joined again; empty when none is left. */
    private static String query(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String parameter : rawQuery.split("&")) {
            if (!parameter.isEmpty() && !isTracking(parameter)) {
                kept.add(parameter);
            }
        }
        kept.sort(null);
        return String.join("&", kept);
    }

    private static boolean isTracking(String parameter) {
        int equals = parameter.indexOf('=');
        String name = (equals < 0 ? parameter : parameter.substring(0, equals)).toLowerCase(Locale.ROOT);
        return TRACKING_NAMES.contains(name) || TRACKING_PREFIXES.stream().anyMatch(name::startsWith);
    }
}
