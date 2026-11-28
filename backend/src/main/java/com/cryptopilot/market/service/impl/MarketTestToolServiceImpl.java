package com.cryptopilot.market.service.impl;

import com.cryptopilot.admin.AdminApi;
import com.cryptopilot.admin.AuditEntry;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.market.dto.request.ReplayStartRequest;
import com.cryptopilot.market.dto.request.ReplayStopRequest;
import com.cryptopilot.market.dto.request.StreamFaultRequest;
import com.cryptopilot.market.dto.response.ReplayResponse;
import com.cryptopilot.market.dto.response.ReplayScenarioResponse;
import com.cryptopilot.market.dto.response.StreamFaultResponse;
import com.cryptopilot.market.job.MarketStreamSupervisor;
import com.cryptopilot.market.job.PriceReplayPlayer;
import com.cryptopilot.market.model.ReplayScenario;
import com.cryptopilot.market.model.ReplayStatus;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.MarketTestToolService;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * RF-07 and RF-12 (see {@link MarketTestToolService}). The scenarios are read once, at start-up, from
 * {@code classpath:market/replay/*.json}, the file's name being the scenario's; a malformed file stops the start, so a
 * broken scenario is found before a demonstration needs it.
 *
 * <p>Rule: RF-07, RF-12 (BM04 section 9); NFR-06; BR-57 (an administrator's change is audited).
 */
@Service
public class MarketTestToolServiceImpl implements MarketTestToolService {

    private static final Logger log = LoggerFactory.getLogger(MarketTestToolServiceImpl.class);
    static final String SCENARIOS = "classpath*:market/replay/*.json";

    private final MarketStreamSupervisor supervisor;
    private final PriceReplayPlayer player;
    private final AdminApi admin;
    private final Clock clock;
    private final Map<String, ReplayScenario> scenarios;

    public MarketTestToolServiceImpl(
            MarketStreamSupervisor supervisor, PriceReplayPlayer player, AdminApi admin, Clock clock, JsonMapper json) {
        this.supervisor = supervisor;
        this.player = player;
        this.admin = admin;
        this.clock = clock;
        this.scenarios = load(json);
    }

    @Override
    @Transactional
    public StreamFaultResponse cutStreams(UUID adminId, StreamFaultRequest request) {
        MarketType market = request.market();
        Duration outage = Duration.ofSeconds(request.outageSeconds());
        Instant cutAt = clock.instant();
        int cut = supervisor.simulateLoss(market, outage);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("key", "market.fault.streamCut." + market.name());
        values.put("outageSeconds", request.outageSeconds());
        values.put("connectionsCut", cut);
        audit(adminId, values);
        return new StreamFaultResponse(market, cut, cutAt, cutAt.plus(outage));
    }

    @Override
    public List<ReplayScenarioResponse> scenarios() {
        return scenarios.values().stream()
                .map(scenario -> new ReplayScenarioResponse(
                        scenario.name(),
                        scenario.description(),
                        scenario.market(),
                        scenario.symbol(),
                        scenario.length().toSeconds(),
                        scenario.points().getFirst().price(),
                        scenario.points().getLast().price()))
                .toList();
    }

    @Override
    public List<ReplayResponse> replays() {
        return player.running().stream()
                .map(MarketTestToolServiceImpl::response)
                .toList();
    }

    @Override
    @Transactional
    public ReplayResponse startReplay(UUID adminId, ReplayStartRequest request) {
        ReplayScenario scenario = scenarios.get(request.scenario().strip());
        if (scenario == null) {
            throw new ResourceNotFoundException("Replay scenario", request.scenario());
        }
        ReplayStatus started = player.start(scenario)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.DATA_CONFLICT,
                        scenario.market() + " does not stream " + scenario.symbol()
                                + " now: enable the pair, with the streams on, before replaying it"));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("key", "market.replay." + scenario.market().name() + "." + scenario.symbol());
        values.put("scenario", scenario.name());
        audit(adminId, values);
        return response(started);
    }

    @Override
    @Transactional
    public void stopReplay(UUID adminId, ReplayStopRequest request) {
        String symbol = request.symbol().strip();
        if (!player.stop(request.market(), symbol)) {
            throw new BusinessException(
                    ErrorCode.DATA_CONFLICT, "no replay runs on " + request.market() + " " + symbol);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("key", "market.replay." + request.market().name() + "." + symbol);
        values.put("scenario", null);
        audit(adminId, values);
    }

    private void audit(UUID adminId, Map<String, Object> values) {
        admin.audit(new AuditEntry(
                adminId, AuditAction.CONFIGURATION_CHANGED, AuditedEntity.SYSTEM_SETTING, null, null, values));
    }

    private static ReplayResponse response(ReplayStatus status) {
        return new ReplayResponse(
                status.scenario(),
                status.market(),
                status.symbol(),
                status.startedAt(),
                status.endsAt(),
                status.price());
    }

    /** Every scenario file, by name; a malformed one fails the start with the file named. */
    static Map<String, ReplayScenario> load(JsonMapper json) {
        Map<String, ReplayScenario> found = new TreeMap<>();
        Resource[] files;
        try {
            files = new PathMatchingResourcePatternResolver().getResources(SCENARIOS);
        } catch (IOException failure) {
            throw new IllegalStateException("RF-07 replay scenarios not listed", failure);
        }
        for (Resource file : files) {
            String fileName = file.getFilename();
            if (fileName == null || !fileName.endsWith(".json")) {
                continue;
            }
            String name = fileName.substring(0, fileName.length() - ".json".length());
            try (InputStream in = file.getInputStream()) {
                found.put(name, scenario(name, json.readTree(in)));
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException("RF-07 replay scenario " + fileName + " is malformed", failure);
            }
        }
        log.info("RF-07 {} replay scenarios: {}", found.size(), found.keySet());
        return found;
    }

    private static ReplayScenario scenario(String name, JsonNode root) {
        List<ReplayScenario.Point> points = new ArrayList<>();
        for (JsonNode point : root.required("points")) {
            points.add(new ReplayScenario.Point(
                    point.required("at").longValue(),
                    new BigDecimal(point.required("price").stringValue())));
        }
        return new ReplayScenario(
                name,
                root.has("description") ? root.get("description").stringValue() : "",
                MarketType.valueOf(root.required("market").stringValue()),
                root.required("symbol").stringValue(),
                root.has("stepMillis") ? root.get("stepMillis").longValue() : 1000,
                new BigDecimal(root.required("spread").stringValue()),
                points);
    }
}
