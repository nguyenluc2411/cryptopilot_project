package com.cryptopilot.admin.service;

import com.cryptopilot.admin.dto.response.SystemEventResponse;
import com.cryptopilot.admin.dto.response.SystemHealthResponse;
import com.cryptopilot.admin.model.enums.SystemEventCategory;
import com.cryptopilot.admin.model.enums.SystemEventSource;
import com.cryptopilot.common.web.PageResponse;
import java.time.LocalDate;

/**
 * The system log and the health panel of SCR-44. Implemented by
 * {@link com.cryptopilot.admin.service.impl.SystemMonitorServiceImpl}.
 *
 * <p>Rule: UC-46.
 */
public interface SystemMonitorService {

    /**
     * One page of the system log, the latest seen first; the period (business days, UTC+7, both ends included)
     * applies to when a failure was last seen.
     *
     * @throws com.cryptopilot.common.exception.FieldValidationException MSG15 on {@code from} when it is after
     *     {@code to}, or on {@code page} or {@code pageSize}
     */
    PageResponse<SystemEventResponse> events(
            SystemEventCategory category,
            SystemEventSource source,
            LocalDate from,
            LocalDate to,
            Integer page,
            Integer pageSize);

    /** The status of each external service and of each market's data feed, now. */
    SystemHealthResponse health();

    /**
     * Deletes the system log rows last seen more than 90 days ago (NSF-17); the audit trail is not touched.
     *
     * @return how many rows were deleted
     */
    int deleteExpired();
}
