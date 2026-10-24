package com.cryptopilot.market.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.market.dto.request.LeverageBracketsRequest;
import com.cryptopilot.market.dto.request.RegisterPairRequest;
import com.cryptopilot.market.dto.request.UpdatePairRequest;
import com.cryptopilot.market.dto.response.AdminPairResponse;
import com.cryptopilot.market.dto.response.ExchangeSymbolResponse;
import com.cryptopilot.market.dto.response.LeverageBracketsResponse;
import com.cryptopilot.market.model.enums.PairStatus;
import com.cryptopilot.market.service.PairAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SCR-37 and SCR-38 of the administration console: supported pairs and their leverage brackets (UC-45, UC-46). Admin
 * only, by the {@code /api/v1/admin/**} rule of the security chain.
 *
 * <p>Rule: UC-45, UC-46; BR-07, BR-26, BR-27, BR-57, BR-59.
 */
@Tag(name = "Admin: pairs", description = "Supported pairs and leverage brackets, SCR-37 and SCR-38 (UC-45, UC-46).")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/admin/pairs")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PairAdminController {

    private final PairAdminService service;

    @Operation(summary = "The pairs of the system, in display order (SCR-37)")
    @ApiResponse(responseCode = "200", description = "Every pair, with its switches and the exchange's status")
    @GetMapping
    public List<AdminPairResponse> list(
            @Parameter(description = "Only symbols containing this, e.g. BTC") @RequestParam(required = false) String q,
            @Parameter(description = "ACTIVE or INACTIVE") @RequestParam(required = false) PairStatus status) {
        return service.list(q, status);
    }

    @Operation(summary = "Symbols the exchange trades that are not pairs yet, read from the exchange now")
    @ApiResponse(responseCode = "200", description = "At most 50, by symbol")
    @ApiResponse(responseCode = "503", description = "MSG43: the exchange cannot be reached")
    @GetMapping("/exchange-symbols")
    public List<ExchangeSymbolResponse> exchangeSymbols(
            @Parameter(description = "Only symbols containing this") @RequestParam(required = false) String q) {
        return service.exchangeSymbols(q);
    }

    @Operation(summary = "Add a pair from the exchange's symbols, INACTIVE with both markets off (UC-45)")
    @ApiResponse(responseCode = "201", description = "The pair")
    @ApiResponse(responseCode = "409", description = "MSG43: already a pair, or the exchange trades it nowhere")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminPairResponse register(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody RegisterPairRequest request) {
        return service.register(UUID.fromString(caller.getSubject()), request.symbol());
    }

    @Operation(summary = "One pair")
    @ApiResponse(responseCode = "200", description = "The pair")
    @ApiResponse(responseCode = "404", description = "MSG41: no such pair")
    @GetMapping("/{id}")
    public AdminPairResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @Operation(summary = "Set the status, market switches, maximum leverage and display order (UC-45, BR-59)")
    @ApiResponse(
            responseCode = "200",
            description = "The pair; ACTIVE plans of a market taken out of service are cancelled")
    @ApiResponse(responseCode = "400", description = "MSG01 or MSG15: `errors` names each rejected field")
    @ApiResponse(responseCode = "404", description = "MSG41: no such pair")
    @ApiResponse(
            responseCode = "409",
            description = "MSG40: open simulated positions; MSG43: the exchange does not trade it")
    @PutMapping("/{id}")
    public AdminPairResponse update(
            @AuthenticationPrincipal Jwt caller, @PathVariable UUID id, @Valid @RequestBody UpdatePairRequest request) {
        return service.update(UUID.fromString(caller.getSubject()), id, request);
    }

    @Operation(summary = "The pair's leverage brackets (SCR-38)")
    @ApiResponse(responseCode = "200", description = "Smallest notional first; empty when none are entered")
    @ApiResponse(responseCode = "404", description = "MSG41: no such pair")
    @GetMapping("/{id}/brackets")
    public LeverageBracketsResponse brackets(@PathVariable UUID id) {
        return service.brackets(id);
    }

    @Operation(summary = "Replace the pair's leverage brackets (UC-46)")
    @ApiResponse(responseCode = "200", description = "The stored brackets, maintenance amounts computed")
    @ApiResponse(responseCode = "400", description = "MSG01 or MSG15: `errors` names each rejected row field")
    @ApiResponse(responseCode = "404", description = "MSG41: no such pair")
    @ApiResponse(responseCode = "409", description = "MSG43: never listed on futures")
    @PutMapping("/{id}/brackets")
    public LeverageBracketsResponse replaceBrackets(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID id,
            @Valid @RequestBody LeverageBracketsRequest request) {
        return service.replaceBrackets(UUID.fromString(caller.getSubject()), id, request);
    }
}
