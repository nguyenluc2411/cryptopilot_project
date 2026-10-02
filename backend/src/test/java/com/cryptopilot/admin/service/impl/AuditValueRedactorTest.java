package com.cryptopilot.admin.service.impl;

import static com.cryptopilot.admin.service.impl.AuditValueRedactor.REDACTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuditValueRedactorTest {

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

        Map<String, Object> redacted = AuditValueRedactor.redact(values);

        assertThat(redacted)
                .containsEntry("model", "configured-model")
                .containsEntry("apiKey", REDACTED)
                .containsEntry("previous", null)
                .containsEntry("nested", Map.of("password", REDACTED, "limit", 5))
                .containsEntry("items", List.of(Map.of("token", REDACTED), "plain"));
    }

    @Test
    void NSF18_noValues_staysNull() {
        assertThat(AuditValueRedactor.redact(null)).isNull();
    }
}
