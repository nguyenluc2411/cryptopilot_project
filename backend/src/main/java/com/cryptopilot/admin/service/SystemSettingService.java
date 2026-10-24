package com.cryptopilot.admin.service;

import com.cryptopilot.admin.dto.response.SystemSettingResponse;
import java.util.List;
import java.util.UUID;

/**
 * The system settings of SCR-43 (UC-51): limits, fees and thresholds an administrator changes without a redeployment.
 * Implemented by {@link com.cryptopilot.admin.service.impl.SystemSettingServiceImpl}.
 *
 * <p>Rule: SRS 3.11.8, UC-51; BR-57.
 */
public interface SystemSettingService {

    /** Every setting, by key. */
    List<SystemSettingResponse> list();

    /** One setting; MSG41 when the key is unknown. */
    SystemSettingResponse get(String key);

    /**
     * Changes a setting's value, checked against its type and range, and audits the old and new values (BR-57). New
     * operations read the new value; stored plans keep their snapshot (BR-31).
     *
     * @throws com.cryptopilot.common.exception.FieldValidationException {@code errors.value} MSG15 when the value does
     *     not fit
     */
    SystemSettingResponse update(UUID adminId, String key, String value);
}
