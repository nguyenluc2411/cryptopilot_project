package com.cryptopilot.watchlist.service;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.watchlist.dto.request.CreateAlertRequest;
import com.cryptopilot.watchlist.dto.request.UpdateAlertRequest;
import com.cryptopilot.watchlist.dto.response.AlertResponse;
import com.cryptopilot.watchlist.model.enums.AlertStatus;
import com.cryptopilot.watchlist.model.enums.AlertType;
import java.util.UUID;

/**
 * The Trader's alert rules (UC-13, UC-14). Every method acts on the caller's own alerts only; another Trader's alert
 * answers as if it did not exist. Implemented by {@link com.cryptopilot.watchlist.service.impl.AlertServiceImpl}.
 *
 * <p>Rule: BR-16, BR-17, BR-19, BR-20, BR-62; UC-13, UC-14; D-48, D-63.
 */
public interface AlertService {

    /**
     * The caller's alerts, newest first.
     *
     * @param status only this status, or {@code null} for all
     * @param type only this type, or {@code null} for both
     * @param page from 1, or {@code null} for the first
     * @param pageSize 1 to 100, or {@code null} for 20
     */
    PageResponse<AlertResponse> list(UUID userId, AlertStatus status, AlertType type, Integer page, Integer pageSize);

    /**
     * Creates an ACTIVE alert; a pair not yet watched is added to the watchlist in the same transaction (BR-16).
     *
     * @throws com.cryptopilot.common.exception.FieldValidationException MSG01, MSG15 for a rule that does not hold
     * @throws com.cryptopilot.common.exception.BusinessException MSG29 for a feature outside the plan, MSG27 at the
     *     ACTIVE alert or the watchlist limit
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException MSG41 when the pair is not enabled on the
     *     market
     */
    AlertResponse create(UUID userId, CreateAlertRequest request);

    /** Replaces the rule of one of the caller's alerts; a TRIGGERED or EXPIRED alert becomes ACTIVE again. */
    AlertResponse update(UUID userId, UUID alertId, UpdateAlertRequest request);

    /** ACTIVE to PAUSED. */
    AlertResponse pause(UUID userId, UUID alertId);

    /** PAUSED to ACTIVE, within the plan's limit and on a pair still enabled on the market. */
    AlertResponse resume(UUID userId, UUID alertId);

    void delete(UUID userId, UUID alertId);
}
