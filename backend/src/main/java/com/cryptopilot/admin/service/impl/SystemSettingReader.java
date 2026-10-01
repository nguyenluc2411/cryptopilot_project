package com.cryptopilot.admin.service.impl;

import com.cryptopilot.admin.SystemSettingApi;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads {@code system_setting} for other modules. Read only: the administration screens that change a setting
 * (UC-40) come with their own task. No cache, so a changed value applies to the next calculation.
 *
 * <p>Rule: SRS 3.11.8; TECHNICAL_DESIGN section 5.5 ({@code system_setting} is a table, not an entity).
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SystemSettingReader implements SystemSettingApi {

    private static final String DECIMAL = "DECIMAL";

    private final JdbcClient jdbc;

    @Override
    @Transactional(readOnly = true)
    public BigDecimal decimal(String key) {
        String[] setting = jdbc.sql("select setting_value, value_type from system_setting where setting_key = ?")
                .param(key)
                .query((row, n) -> new String[] {row.getString("setting_value"), row.getString("value_type")})
                .optional()
                .orElseThrow(() -> new IllegalStateException("system setting " + key + " is not stored"));
        if (!DECIMAL.equals(setting[1])) {
            throw new IllegalStateException("system setting " + key + " is " + setting[1] + ", not " + DECIMAL);
        }
        return new BigDecimal(setting[0]);
    }
}
