package com.cryptopilot.admin;

import java.math.BigDecimal;

/**
 * What the {@code admin} module tells other modules about the system settings an administrator maintains: the values
 * of {@code system_setting}, read by key. Other modules read a limit, fee or threshold here rather than hard-coding
 * it; this module owns the table and is the only one that writes it.
 *
 * <p>Rule: SRS 3.11.8 (system settings); TECHNICAL_DESIGN section 2 (a module's table is reached through its API).
 */
public interface SystemSettingApi {

    /**
     * The decimal value of a setting.
     *
     * @throws IllegalStateException when the setting is not stored or is not a {@code DECIMAL}: a missing seed row is
     *     a deployment defect, not a request error
     */
    BigDecimal decimal(String key);
}
