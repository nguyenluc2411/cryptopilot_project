package com.cryptopilot.admin.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code system_setting}.
 *
 * @param key e.g. {@code SPOT_TAKER_FEE}
 * @param value the value as text, in the form of its type
 * @param valueType INT, DECIMAL, BOOLEAN or STRING
 * @param description what the setting does, or {@code null}
 * @param updatedBy the administrator who last changed it, or {@code null} for the seed
 * @param updatedAt when it last changed
 */
public record StoredSetting(
        String key, String value, String valueType, String description, UUID updatedBy, Instant updatedAt) {}
