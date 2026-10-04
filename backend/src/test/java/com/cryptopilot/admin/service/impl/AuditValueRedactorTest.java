package com.cryptopilot.admin.service.impl;

import static com.cryptopilot.admin.service.impl.AuditValueRedactor.REDACTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AuditValueRedactorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "password",
                "passwordHash",
                "password_hash",
                "newPassword",
                "refreshToken",
                "token_hash",
                "otp",
                "otpCode",
                "resetToken",
                "apiKey",
                "api_key",
                "apikey",
                "privateKey",
                "secretKey",
                "clientSecret",
                "credentials"
            })
    void NSF18_aFieldNamedForASecret_isRedacted(String field) {
        assertThat(AuditValueRedactor.isSecret(field)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sessionId", "sid", "cookie", "authorization", "bearerToken", "bearer", "jwt", "hash"})
    void NSF18_sessionAndAuthorizationKeys_areRedacted(String field) {
        assertThat(AuditValueRedactor.isSecret(field)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"accountStatus", "role", "key", "settingKey", "setting_key", "footprint", "email", "keyword", ""
            })
    void NSF18_anOrdinaryField_isKept(String field) {
        assertThat(AuditValueRedactor.isSecret(field)).isFalse();
    }

    @Test
    void NSF18_secretsInsideNestedValues_areRedactedAndTheFieldStays() {
        Map<String, Object> values = new HashMap<>();
        values.put("model", "configured-model");
        values.put("apiKey", "sk-live-123");
        values.put("previous", null);
        values.put("nested", Map.of("password", "hunter2", "limit", 5));
        values.put("items", List.of(Map.of("token", "abc"), "plain"));

        JsonNode redacted = AuditValueRedactor.redact(JSON.valueToTree(values));

        Map<String, Object> expected = new HashMap<>();
        expected.put("model", "configured-model");
        expected.put("apiKey", REDACTED);
        expected.put("previous", null);
        expected.put("nested", Map.of("password", REDACTED, "limit", 5));
        expected.put("items", List.of(Map.of("token", REDACTED), "plain"));
        assertThat(redacted).isEqualTo(JSON.valueToTree(expected));
    }

    @Test
    void NSF18_noValues_staysNull() {
        assertThat(AuditValueRedactor.redact(null)).isNull();
    }
}
