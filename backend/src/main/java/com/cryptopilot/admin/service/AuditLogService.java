package com.cryptopilot.admin.service;

import com.cryptopilot.admin.dto.response.AuditLogEntryResponse;
import com.cryptopilot.admin.model.enums.ActorType;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import com.cryptopilot.common.web.PageResponse;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * The audit log of SCR-44, read only. A period is a range of business days, UTC+7 (CR-01), both ends included.
 * Implemented by {@link com.cryptopilot.admin.service.impl.AuditLogServiceImpl}.
 *
 * <p>Rule: SRS 3.11.9; UC-46; BR-57.
 */
public interface AuditLogService {

    /** The criteria of a search; every one {@code null} or empty is no restriction. */
    record Criteria(
            String actor,
            UUID actorId,
            ActorType actorType,
            Set<AuditAction> actions,
            AuditedEntity entityType,
            UUID entityId,
            LocalDate from,
            LocalDate to) {}

    /**
     * One page of entries, newest first.
     *
     * @throws com.cryptopilot.common.exception.FieldValidationException MSG15 on {@code from} when it is after
     *     {@code to}, or on {@code page} or {@code pageSize}
     */
    PageResponse<AuditLogEntryResponse> search(Criteria criteria, Integer page, Integer pageSize);

    /**
     * The entries of the same search as CSV, newest first, at most {@link #EXPORT_LIMIT}.
     *
     * @throws com.cryptopilot.common.exception.FieldValidationException MSG15 on {@code from} when it is after
     *     {@code to}
     */
    String csv(Criteria criteria);

    /** The most rows one export holds. */
    int EXPORT_LIMIT = 10_000;
}
