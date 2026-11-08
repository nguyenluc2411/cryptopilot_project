package com.cryptopilot.billing.dto;

import java.util.Map;

/**
 * How the browser reaches the gateway's payment page: open {@code url} for a GET, or submit {@code fields} to it as a
 * form for a POST.
 */
public record CheckoutResponse(String method, String url, Map<String, String> fields) {}
