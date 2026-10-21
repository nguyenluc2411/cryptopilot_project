package com.cryptopilot.admin.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One system setting as SCR-43 shows it, with the range its value may take.
 *
 * <p>Rule: SRS 3.11.8, UC-51.
 *
 * @param key e.g. {@code SPOT_TAKER_FEE}
 * @param value the value as text
 * @param valueType INT, DECIMAL, BOOLEAN or STRING
 * @param description what the setting does
 * @param min the lowest value allowed, or {@code null} when only the type is checked
 * @param max the highest value allowed, or {@code null}
 * @param updatedBy the administrator who last changed it, or {@code null} for the seeded value
 * @param updatedAt when it last changed
 */
public record SystemSettingResponse(
        String key,
        String value,
        String valueType,
        String description,
        BigDecimal min,
        BigDecimal max,
        UUID updatedBy,
        Instant updatedAt) {}
