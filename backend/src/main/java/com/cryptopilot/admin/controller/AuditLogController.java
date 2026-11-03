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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit log of SCR-44 (UC-46, SRS 3.11.9): searched by actor, action, entity and period, read only, with a CSV
 * export. Admin only, by the {@code /api/v1/admin/**} rule of the security chain. Dates are business days, UTC+7.
 *
 * <p>Rule: SRS 3.11.9; UC-46; BR-57.
 */
@Tag(name = "Admin: audit log", description = "Administrative and security-relevant actions, SCR-44 (UC-46).")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/admin/audit-log")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AuditLogController {

    private final AuditLogService service;

    @Operation(summary = "One page of the audit log, newest first (SCR-44)")
    @ApiResponse(responseCode = "200", description = "20 entries by default")
    @ApiResponse(responseCode = "400", description = "MSG01: an unknown filter value; MSG15: period, page or pageSize")
    @GetMapping
    public PageResponse<AuditLogEntryResponse> search(
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
        return service.search(
                criteria(actor, actorId, actorType, action, entityType, entityId, from, to), page, pageSize);
    }

    @Operation(summary = "The same search as a CSV file, at most 10,000 entries")
    @ApiResponse(responseCode = "200", description = "text/csv, a header line then one line per entry")
    @ApiResponse(responseCode = "400", description = "MSG01: an unknown filter value; MSG15: the period")
    @GetMapping("/export")
    public ResponseEntity<String> export(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) ActorType actorType,
            @RequestParam(required = false) List<AuditAction> action,
            @RequestParam(required = false) AuditedEntity entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String csv = service.csv(criteria(actor, actorId, actorType, action, entityType, entityId, from, to));
        String name =
                "cryptopilot-audit-log" + (from == null ? "" : "-" + from) + (to == null ? "" : "-" + to) + ".csv";
        return ResponseEntity.ok()
                .contentType(DashboardController.CSV)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name).build().toString())
                .body(csv);
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
