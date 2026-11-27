package com.cryptopilot.market.controller;

import com.cryptopilot.market.dto.request.ReplayStartRequest;
import com.cryptopilot.market.dto.request.ReplayStopRequest;
import com.cryptopilot.market.dto.request.StreamFaultRequest;
import com.cryptopilot.market.dto.response.ReplayResponse;
import com.cryptopilot.market.dto.response.ReplayScenarioResponse;
import com.cryptopilot.market.dto.response.StreamFaultResponse;
import com.cryptopilot.market.service.MarketTestToolService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The market tools of a test environment (RF-07, RF-12; BM04 section 9), for an administrator: the price replay that
 * drives a pair along a scripted path, and the fault switch that cuts a market's exchange streams.
 *
 * <p>Present only where {@code cryptopilot.market.test-tools.enabled} is on, the {@code dev} and {@code demo} (staging)
 * profiles; elsewhere, production included, these paths do not exist.
 *
 * <p>Rule: RF-07, RF-12; NFR-06; BR-57.
 */
@Tag(name = "Admin: market test tools", description = "Price replay and stream fault switch of a test environment.")
@RestController
@RequestMapping("/api/v1/admin/market")
@ConditionalOnProperty(prefix = "cryptopilot.market.test-tools", name = "enabled", havingValue = "true")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MarketTestToolController {

    private final MarketTestToolService service;

    @Operation(summary = "Cut a market's exchange streams for a while (RF-12, audited)")
    @ApiResponse(responseCode = "200", description = "How many connections were cut and when they reconnect")
    @ApiResponse(responseCode = "400", description = "MSG01: `errors` names each rejected field")
    @PostMapping("/fault/stream-cut")
    public StreamFaultResponse cutStreams(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody StreamFaultRequest request) {
        return service.cutStreams(UUID.fromString(caller.getSubject()), request);
    }

    @Operation(summary = "The price replay scenarios this environment can play (RF-07)")
    @ApiResponse(responseCode = "200", description = "Every scenario, by name")
    @GetMapping("/replay/scenarios")
    public List<ReplayScenarioResponse> scenarios() {
        return service.scenarios();
    }

    @Operation(summary = "The price replays running now (RF-07)")
    @ApiResponse(responseCode = "200", description = "Every running replay")
    @GetMapping("/replay")
    public List<ReplayResponse> replays() {
        return service.replays();
    }

    @Operation(summary = "Start a price replay on its pair, replacing the one running there (RF-07, audited)")
    @ApiResponse(responseCode = "200", description = "The replay started")
    @ApiResponse(responseCode = "400", description = "MSG01: `errors` names each rejected field")
    @ApiResponse(responseCode = "404", description = "No scenario has that name")
    @ApiResponse(responseCode = "409", description = "DATA_CONFLICT: the market does not stream the pair now")
    @PostMapping("/replay")
    public ReplayResponse startReplay(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody ReplayStartRequest request) {
        return service.startReplay(UUID.fromString(caller.getSubject()), request);
    }

    @Operation(summary = "Stop the price replay of a pair, back to the exchange's prices (RF-07, audited)")
    @ApiResponse(responseCode = "204", description = "Stopped")
    @ApiResponse(responseCode = "400", description = "MSG01: `errors` names each rejected field")
    @ApiResponse(responseCode = "409", description = "DATA_CONFLICT: no replay runs on that pair")
    @PostMapping("/replay/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stopReplay(@AuthenticationPrincipal Jwt caller, @Valid @RequestBody ReplayStopRequest request) {
        service.stopReplay(UUID.fromString(caller.getSubject()), request);
    }
}
