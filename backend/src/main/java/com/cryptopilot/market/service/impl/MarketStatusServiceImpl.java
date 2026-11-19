package com.cryptopilot.market.service.impl;

import com.cryptopilot.admin.AdminApi;
import com.cryptopilot.admin.AuditEntry;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.market.dto.request.MaintenanceRequest;
import com.cryptopilot.market.dto.request.ResumeRequest;
import com.cryptopilot.market.dto.response.MarketHealthResponse;
import com.cryptopilot.market.dto.response.MarketIncidentResponse;
import com.cryptopilot.market.dto.response.MarketStatusResponse;
import com.cryptopilot.market.model.MarketIncident;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.MarketIncidentRepository;
import com.cryptopilot.market.service.MarketHealthService;
import com.cryptopilot.market.service.MarketStatusService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The markets' data health as the Traders' banner and the administration console read it (D-158): the state of each
 * market from {@link MarketHealthService}, the open incident's times, reason and message, and, for ten minutes after
 * an incident ended, when it did, so the banner can say trading is back; while a market is open, the pairs whose data
 * is lost (E5).
 *
 * <p>Maintenance is audited as a change of a system setting named {@code market.maintenance.<MARKET>}, a reopening
 * by hand as one named {@code market.resume.<MARKET>} (BR-57).
 *
 * <p>Rule: NSF-03; BR-57; D-158.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MarketStatusServiceImpl implements MarketStatusService {

    /** How long after an incident ended the status still names it. */
    static final Duration RESUMED_SHOWN_FOR = Duration.ofMinutes(10);

    private final MarketHealthService health;
    private final MarketIncidentRepository incidents;
    private final AdminApi admin;
    private final MarketLiveness liveness;
    private final Clock clock;

    @Override
    public MarketStatusResponse status() {
        List<MarketHealthResponse> markets = new ArrayList<>();
        for (MarketType market : MarketType.values()) {
            markets.add(of(market));
        }
        return new MarketStatusResponse(List.copyOf(markets));
    }

    @Override
    public List<MarketIncidentResponse> incidents(MarketType market, int limit) {
        if (limit < 1 || limit > 100) {
            throw new FieldValidationException("limit must be 1 to 100, not " + limit, Map.of("limit", "MSG01"));
        }
        return incidents.findRecent(market, limit).stream()
                .map(MarketStatusServiceImpl::response)
                .toList();
    }

    @Override
    @Transactional
    public MarketStatusResponse maintenance(UUID adminId, MaintenanceRequest request) {
        MarketType market = request.market();
        boolean before = health.state(market) == MarketHealthState.MAINTENANCE;
        boolean on = request.on();
        if (on) {
            health.startMaintenance(market, blankToNull(request.message()), adminId);
        } else {
            health.endMaintenance(market);
        }
        if (before != on) {
            admin.audit(new AuditEntry(
                    adminId,
                    AuditAction.CONFIGURATION_CHANGED,
                    AuditedEntity.SYSTEM_SETTING,
                    null,
                    setting(market, before, null),
                    setting(market, on, on ? blankToNull(request.message()) : null)));
        }
        return status();
    }

    @Override
    @Transactional
    public MarketStatusResponse resume(UUID adminId, ResumeRequest request) {
        MarketType market = request.market();
        health.forceResume(market, adminId);
        Map<String, Object> resumed = new LinkedHashMap<>();
        resumed.put("key", "market.resume." + market.name());
        resumed.put("on", true);
        admin.audit(new AuditEntry(
                adminId, AuditAction.CONFIGURATION_CHANGED, AuditedEntity.SYSTEM_SETTING, null, null, resumed));
        return status();
    }

    private MarketHealthResponse of(MarketType market) {
        MarketHealthState state = health.state(market);
        if (!state.tradingOpen()) {
            Optional<MarketIncident> open = incidents.findOpen(market);
            if (open.isPresent()) {
                MarketIncident incident = open.get();
                return new MarketHealthResponse(
                        market,
                        state,
                        false,
                        incident.lastLiveAt(),
                        incident.pausedAt(),
                        incident.reason(),
                        incident.message(),
                        incident.id(),
                        null,
                        List.of());
            }
            return new MarketHealthResponse(market, state, false, null, null, null, null, null, null, List.of());
        }
        Instant shownAfter = clock.instant().minus(RESUMED_SHOWN_FOR);
        Optional<MarketIncident> ended = incidents.findRecent(market, 1).stream()
                .filter(incident ->
                        incident.resumedAt() != null && incident.resumedAt().isAfter(shownAfter))
                .findFirst();
        return new MarketHealthResponse(
                market,
                state,
                true,
                null,
                ended.map(MarketIncident::pausedAt).orElse(null),
                null,
                null,
                ended.map(MarketIncident::id).orElse(null),
                ended.map(MarketIncident::resumedAt).orElse(null),
                liveness.silentSymbols(market));
    }

    private static MarketIncidentResponse response(MarketIncident incident) {
        return new MarketIncidentResponse(
                incident.id(),
                incident.market(),
                incident.kind(),
                incident.reason(),
                incident.lastLiveAt(),
                incident.pausedAt(),
                incident.recoveryStartedAt(),
                incident.resumedAt(),
                incident.message(),
                incident.createdBy());
    }

    private static Map<String, Object> setting(MarketType market, boolean on, String message) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("key", "market.maintenance." + market.name());
        values.put("on", on);
        if (message != null) {
            values.put("message", message);
        }
        return values;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
