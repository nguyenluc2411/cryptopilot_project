package com.cryptopilot.admin.service.impl;

import com.cryptopilot.admin.ActorDirectory;
import com.cryptopilot.admin.ActorDirectory.ActorSummary;
import com.cryptopilot.admin.AdminMetricSource;
import com.cryptopilot.admin.dto.response.AuditLogEntryResponse;
import com.cryptopilot.admin.model.AuditLogFilter;
import com.cryptopilot.admin.model.AuditLogRow;
import com.cryptopilot.admin.model.enums.ActorType;
import com.cryptopilot.admin.repository.AuditLogReader;
import com.cryptopilot.admin.service.AuditLogService;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Searches the audit trail for SCR-44 and exports it. The actor filter by text is answered by {@code user} through
 * {@link ActorDirectory}: the accounts whose email or display name contains it, at most {@link #ACTOR_MATCHES}, the
 * newest first, are the ones searched.
 *
 * <p>The export opens in a spreadsheet, so a cell that a spreadsheet would read as a formula ({@code =}, {@code +},
 * {@code -}, {@code @}, tab or carriage return first) is prefixed with an apostrophe; a stored value, an email or a
 * reason given by an administrator is never run as one (OWASP, "CSV Injection").
 *
 * <p>Rule: SRS 3.11.9; UC-46; BR-57; CR-01.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AuditLogServiceImpl implements AuditLogService {

    /** How many accounts an actor filter by text may stand for. */
    static final int ACTOR_MATCHES = 500;

    private static final ZoneId ZONE = ZoneId.of(AdminMetricSource.ZONE);
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZONE);

    private final AuditLogReader reader;
    private final ActorDirectory actors;
    private final ObjectMapper json;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuditLogEntryResponse> search(Criteria criteria, Integer page, Integer pageSize) {
        PageRequest request = Paging.of(page, pageSize);
        AuditLogFilter filter = filter(criteria);
        long total = reader.count(filter);
        List<AuditLogRow> rows =
                total == 0 ? List.of() : reader.search(filter, request.getOffset(), request.getPageSize());
        return new PageResponse<>(responses(rows), request.getPageNumber() + 1, request.getPageSize(), total);
    }

    @Override
    @Transactional(readOnly = true)
    public String csv(Criteria criteria) {
        List<AuditLogEntryResponse> entries = responses(reader.search(filter(criteria), 0, EXPORT_LIMIT));
        StringBuilder csv = new StringBuilder(
                "recordedAt,actorEmail,actorId,action,entityType,entityId,ipAddress,oldValue,newValue\r\n");
        for (AuditLogEntryResponse entry : entries) {
            AuditLogEntryResponse.Actor actor = entry.actor();
            csv.append(String.join(
                            ",",
                            cell(CSV_TIME.format(entry.recordedAt())),
                            cell(actor == null ? "SYSTEM" : actor.email()),
                            cell(actor == null ? null : actor.id().toString()),
                            cell(entry.action()),
                            cell(entry.entityType()),
                            cell(
                                    entry.entityId() == null
                                            ? null
                                            : entry.entityId().toString()),
                            cell(entry.ipAddress()),
                            cell(entry.oldValue() == null ? null : json.writeValueAsString(entry.oldValue())),
                            cell(entry.newValue() == null ? null : json.writeValueAsString(entry.newValue()))))
                    .append("\r\n");
        }
        return csv.toString();
    }

    private AuditLogFilter filter(Criteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        if (criteria.from() != null && criteria.to() != null && criteria.from().isAfter(criteria.to())) {
            throw new FieldValidationException("the period starts after it ends", Map.of("from", "MSG15"));
        }
        Set<UUID> only = null;
        String text = criteria.actor() == null || criteria.actor().isBlank()
                ? null
                : criteria.actor().strip();
        if (text != null) {
            only = new HashSet<>(actors.matching(text, ACTOR_MATCHES));
        }
        if (criteria.actorId() != null) {
            if (only == null) {
                only = Set.of(criteria.actorId());
            } else {
                only.retainAll(Set.of(criteria.actorId()));
            }
        }
        return new AuditLogFilter(
                only,
                criteria.actorType() == ActorType.SYSTEM,
                criteria.actorType() == ActorType.USER,
                criteria.actions(),
                criteria.entityType(),
                criteria.entityId(),
                start(criteria.from()),
                criteria.to() == null ? null : start(criteria.to().plusDays(1)));
    }

    private List<AuditLogEntryResponse> responses(List<AuditLogRow> rows) {
        Set<UUID> ids = new HashSet<>();
        rows.forEach(row -> {
            if (row.actorId() != null) {
                ids.add(row.actorId());
            }
        });
        Map<UUID, ActorSummary> known = actors.describe(ids);
        return rows.stream()
                .map(row -> new AuditLogEntryResponse(
                        row.id(),
                        row.recordedAt(),
                        actor(row.actorId(), known),
                        row.action(),
                        row.entityType(),
                        row.entityId(),
                        row.ipAddress(),
                        tree(row.oldValue()),
                        tree(row.newValue())))
                .toList();
    }

    private static AuditLogEntryResponse.Actor actor(UUID id, Map<UUID, ActorSummary> known) {
        if (id == null) {
            return null;
        }
        ActorSummary summary = known.get(id);
        return summary == null
                ? new AuditLogEntryResponse.Actor(id, null, null, null)
                : new AuditLogEntryResponse.Actor(id, summary.email(), summary.displayName(), summary.role());
    }

    private JsonNode tree(String stored) {
        return stored == null ? null : json.readTree(stored);
    }

    private static Instant start(LocalDate day) {
        return day == null ? null : day.atStartOfDay(ZONE).toInstant();
    }

    /** One CSV cell, RFC 4180 quoted when it needs to be, and never read as a formula. */
    static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = value;
        char first = text.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            text = "'" + text;
        }
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }
}
