package com.cryptopilot.billing.model;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * How the Trader's browser reaches the gateway's payment page: a link to open (MoMo returns one) or a form to post
 * (the SePay checkout is a signed form). The client follows it as is.
 *
 * @param method {@code GET} to open {@code url}, {@code POST} to submit {@code fields} to it as a form
 * @param url the gateway's page
 * @param fields the form fields of a POST, in order; empty for a GET
 */
public record Checkout(String method, URI url, Map<String, String> fields) {

    public Checkout {
        Objects.requireNonNull(url, "url must not be null");
        if (!"GET".equals(method) && !"POST".equals(method)) {
            throw new IllegalArgumentException("a checkout is a GET or a POST, was " + method);
        }
        fields = fields == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
    }

    /** A page to open. */
    public static Checkout link(URI url) {
        return new Checkout("GET", url, Map.of());
    }

    /** A form to post, its fields in the order given. */
    public static Checkout form(URI url, Map<String, String> fields) {
        return new Checkout("POST", url, fields);
    }
}
