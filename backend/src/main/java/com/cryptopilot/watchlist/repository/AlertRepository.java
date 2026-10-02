package com.cryptopilot.watchlist.repository;

import com.cryptopilot.watchlist.entity.Alert;
import com.cryptopilot.watchlist.model.enums.AlertStatus;
import com.cryptopilot.watchlist.model.enums.AlertType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to the alert rules. Every lookup names the owner, so another Trader's alert is never loaded. The list has
 * one method per filter combination: no parameter is ever null (D-73).
 *
 * <p>Rule: BR-16, BR-17; UC-13, UC-14.
 */
public interface AlertRepository extends Repository<Alert, UUID> {

    /** The Trader's alert with this key, or empty when there is none or it belongs to someone else. */
    @Transactional(readOnly = true)
    Optional<Alert> findByIdAndUserId(UUID alertId, UUID userId);

    /** How many alerts of the Trader are in this status: ACTIVE is what {@code ACTIVE_ALERT_MAX} counts (BR-17). */
    @Transactional(readOnly = true)
    long countByUserIdAndStatus(UUID userId, AlertStatus status);

    @Transactional(readOnly = true)
    Page<Alert> findByUserId(UUID userId, Pageable page);

    @Transactional(readOnly = true)
    Page<Alert> findByUserIdAndStatus(UUID userId, AlertStatus status, Pageable page);

    @Transactional(readOnly = true)
    Page<Alert> findByUserIdAndType(UUID userId, AlertType type, Pageable page);

    @Transactional(readOnly = true)
    Page<Alert> findByUserIdAndStatusAndType(UUID userId, AlertStatus status, AlertType type, Pageable page);

    /** Writes an alert. Not transactional here; the unit of work is the calling service's. */
    Alert save(Alert alert);

    void delete(Alert alert);
}
