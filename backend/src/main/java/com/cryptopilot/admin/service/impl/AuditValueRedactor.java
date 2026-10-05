package com.cryptopilot.admin.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Replaces the value of every field whose name says it holds a secret — a password or its hash, a token, an OTP or
 * reset code, an API or private key, a credential — before an audit entry is stored. The field itself stays, so the
 * log still shows that it changed. The values are redacted as the JSON tree they are stored as, so nested objects,
 * arrays, records and other beans are all walked the same way: whatever Jackson would write is what is checked.
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

    private static final Set<String> SECRET_WORDS = Set.of(
            "password",
            "passwd",
            "pwd",
            "secret",
            "token",
            "tokens",
            "otp",
            "credential",
            "credentials",
            "hash",
            "session",
            "sid",
            "cookie",
            "authorization",
            "bearer",
            "jwt");

    private static final Set<String> SECRET_KEY_QUALIFIERS = Set.of("api", "private", "access");

    private AuditValueRedactor() {}

    /**
     * Replaces every secret in the tree, in place, and answers it; {@code null} stays {@code null}. The caller passes a
     * tree it owns, built from the audited values with the application's mapper.
     *
     * <p>Rule: NSF-18.
     *
     * <p>Reference: OWASP Foundation. <i>Logging Cheat Sheet</i>, "Data to exclude".
     */
    static JsonNode redact(JsonNode values) {
        if (values instanceof ObjectNode object) {
            List<String> fields = new ArrayList<>(object.propertyNames());
            for (String field : fields) {
                if (isSecret(field)) {
                    object.put(field, REDACTED);
                } else {
                    redact(object.get(field));
                }
            }
        } else if (values instanceof ArrayNode array) {
            array.forEach(AuditValueRedactor::redact);
        }
        return values;
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
