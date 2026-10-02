package com.cryptopilot.admin.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Replaces the value of every field whose name says it holds a secret — a password or its hash, a token, an OTP or
 * reset code, an API or private key, a credential — before an audit entry is stored. The field itself stays, so the
 * log still shows that it changed. Nested maps and lists are walked, because a configuration value can be an object.
 *
 * <p>A name is split into words ({@code passwordHash}, {@code refresh_token}, {@code api-key}) and matched word by
 * word, so {@code footprint} is not mistaken for an OTP and {@code setting_key} is not mistaken for a key.
 *
 * <p>Rule: NSF-18; SRS 4.2.4.
 *
 * <p>Reference: OWASP Foundation. <i>Logging Cheat Sheet</i>, "Data to exclude" (passwords, session identifiers,
 * access tokens, encryption keys). Kent, K. &amp; Souppaya, M. (2006). <i>Guide to Computer Security Log
 * Management</i>. NIST SP 800-92, section 4 (protecting what logs contain).
 */
final class AuditValueRedactor {

    static final String REDACTED = "[REDACTED]";

    private static final Set<String> SECRET_WORDS =
            Set.of("password", "passwd", "pwd", "secret", "token", "tokens", "otp", "credential", "credentials");

    private static final Set<String> SECRET_KEY_QUALIFIERS = Set.of("api", "private", "access", "secret");

    private AuditValueRedactor() {}

    /** A copy of the values with every secret replaced, or {@code null} for {@code null}. */
    static Map<String, Object> redact(Map<String, Object> values) {
        return values == null ? null : redactMap(values);
    }

    private static Map<String, Object> redactMap(Map<?, ?> values) {
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((name, value) -> {
            String field = String.valueOf(name);
            copy.put(field, isSecret(field) ? REDACTED : redactValue(value));
        });
        return copy;
    }

    private static Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return redactMap(map);
        }
        if (value instanceof Collection<?> items) {
            List<Object> copy = new ArrayList<>(items.size());
            items.forEach(item -> copy.add(redactValue(item)));
            return copy;
        }
        return value;
    }

    static boolean isSecret(String field) {
        List<String> words = words(field);
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            if (SECRET_WORDS.contains(word)
                    || word.equals("apikey")
                    || word.equals("key") && i > 0 && SECRET_KEY_QUALIFIERS.contains(words.get(i - 1))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> words(String field) {
        String spaced = field.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("[^A-Za-z0-9]+", " ");
        return Arrays.stream(spaced.trim().toLowerCase(Locale.ROOT).split(" "))
                .filter(word -> !word.isEmpty())
                .toList();
    }
}
