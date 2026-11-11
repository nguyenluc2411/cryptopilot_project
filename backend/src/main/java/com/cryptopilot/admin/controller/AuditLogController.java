package com.cryptopilot.admin.controller;

import com.cryptopilot.admin.dto.response.AuditLogEntryResponse;
import com.cryptopilot.admin.model.enums.ActorType;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import com.cryptopilot.admin.service.AuditLogService;
import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit log of SCR-44 (UC-52, SRS 3.11.9): searched by actor, action, entity and period, read only, with a CSV
 * export. Admin only, by the {@code /api/v1/admin/**} rule of the security chain. Dates are business days, UTC+7.
 *
 * <p>Rule: SRS 3.11.9; UC-52; BR-57.
 */
@Tag(name = "Admin: audit log", description = "Administrative and security-relevant actions, SCR-44 (UC-52).")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/admin/audit-log")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AuditLogController {

    /** Names what a result left out: {@code actor} (the actor text matched too many accounts), {@code export}. */
    static final String TRUNCATED = "X-Result-Truncated";

    /** How many entries matched an export that was cut. */
    static final String TOTAL = "X-Total-Count";

    private final AuditLogService service;

    @Operation(summary = "One page of the audit log, newest first (SCR-44)")
    @ApiResponse(
            responseCode = "200",
            description = "20 entries by default. Header `X-Result-Truncated: actor` when the actor text matched more"
                    + " than 500 accounts and only the newest 500 were searched.")
    @ApiResponse(responseCode = "400", description = "MSG01: an unknown filter value; MSG15: period, page or pageSize")
    @GetMapping
    public ResponseEntity<PageResponse<AuditLogEntryResponse>> search(
            @Parameter(description = "Part of the actor's email or display name") @RequestParam(required = false)
                    String actor,
            @Parameter(description = "The actor's account id") @RequestParam(required = false) UUID actorId,
            @Parameter(description = "USER or SYSTEM") @RequestParam(required = false) ActorType actorType,
            @Parameter(description = "One or more actions, repeated") @RequestParam(required = false)
                    List<AuditAction> action,
            @RequestParam(required = false) AuditedEntity entityType,
            @RequestParam(required = false) UUID entityId,
            @Parameter(description = "First day, e.g. 2026-11-01")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(description = "Last day, included")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        AuditLogService.Page result = service.search(
                criteria(actor, actorId, actorType, action, entityType, entityId, from, to), page, pageSize);
        ResponseEntity.BodyBuilder answer = ResponseEntity.ok();
        if (result.actorsTruncated()) {
            answer.header(TRUNCATED, "actor");
        }
        return answer.body(result.page());
    }

    @Operation(summary = "The same search as a CSV file, at most 10,000 entries")
    @ApiResponse(
            responseCode = "200",
            description = "text/csv, a header line then one line per entry; the export is audited. When more than"
                    + " 10,000 entries match: `X-Total-Count: <n>` and `X-Result-Truncated: export` (`actor,export`"
                    + " when the actor text was cut too, `actor` alone when only it was).")
    @ApiResponse(responseCode = "400", description = "MSG01: an unknown filter value; MSG15: the period")
    @GetMapping("/export")
    public ResponseEntity<String> export(
            @AuthenticationPrincipal Jwt caller,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) ActorType actorType,
            @RequestParam(required = false) List<AuditAction> action,
            @RequestParam(required = false) AuditedEntity entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AuditLogService.Export export = service.export(
                UUID.fromString(caller.getSubject()),
                criteria(actor, actorId, actorType, action, entityType, entityId, from, to));
        String name =
                "cryptopilot-audit-log" + (from == null ? "" : "-" + from) + (to == null ? "" : "-" + to) + ".csv";
        ResponseEntity.BodyBuilder answer = ResponseEntity.ok()
                .contentType(DashboardController.CSV)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name).build().toString());
        List<String> cut = new ArrayList<>();
        if (export.actorsTruncated()) {
            cut.add("actor");
        }
        if (export.truncated()) {
            cut.add("export");
            answer.header(TOTAL, String.valueOf(export.total()));
        }
        if (!cut.isEmpty()) {
            answer.header(TRUNCATED, String.join(",", cut));
        }
        return answer.body(export.csv());
    }

    private static AuditLogService.Criteria criteria(
            String actor,
            UUID actorId,
            ActorType actorType,
            List<AuditAction> actions,
            AuditedEntity entityType,
            UUID entityId,
            LocalDate from,
            LocalDate to) {
        return new AuditLogService.Criteria(
                actor,
                actorId,
                actorType,
                actions == null ? Set.of() : Set.copyOf(actions),
                entityType,
                entityId,
                from,
                to);
    }
}
