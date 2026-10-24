package com.cryptopilot.news.client;

import java.util.Objects;

/**
 * A feed that could not be read, with the reason an administrator or the crawl log can act on. The message names the
 * address and what happened; it never carries the response body.
 */
public class RssClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the feed could not be read. */
    public enum Reason {
        /** The address is not an absolute http or https address with a host. */
        INVALID_ADDRESS,
        /** The host resolves to a loopback, private, link-local or multicast address. */
        BLOCKED_ADDRESS,
        /** The host could not be resolved or reached, or the response did not arrive in time. */
        UNREACHABLE,
        /** The server answered with a status other than 2xx, or redirected too often. */
        HTTP_STATUS,
        /** The response was larger than the configured limit. */
        TOO_LARGE,
        /** The response was not an RSS or Atom feed. */
        NOT_A_FEED
    }

    private final Reason reason;

    public RssClientException(Reason reason, String message) {
        this(reason, message, null);
    }

    public RssClientException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public Reason reason() {
        return reason;
    }
}
