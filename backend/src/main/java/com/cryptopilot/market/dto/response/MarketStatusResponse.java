package com.cryptopilot.market.dto.response;

import java.util.List;

/**
 * The payload of {@code GET /api/v1/market/status} and of {@code /topic/market.status}: every market's data health
 * (D-159).
 *
 * @param markets SPOT, then FUTURES
 */
public record MarketStatusResponse(List<MarketHealthResponse> markets) {}
