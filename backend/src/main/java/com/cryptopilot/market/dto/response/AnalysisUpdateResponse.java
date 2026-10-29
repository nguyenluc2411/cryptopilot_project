package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The payload of {@code /topic/analysis.{market}.{symbol}.{tf}}: the part of the analysis of one closed candle that is
 * the same for every Trader. It carries no setup score and no component scores — the score depends on the reader's
 * preset, so a client re-reads {@code GET /api/v1/analysis/{market}/{symbol}?tf=} for it, and the components have their
 * own topic. Decimals are strings.
 *
 * <p>Rule: NSF-05, UC-10, UC-11; BR-12, BR-14; TECHNICAL_DESIGN 9; D-51, D-126.
 *
 * @param market {@code SPOT} or {@code FUTURES}
 * @param symbol the symbol
 * @param timeframe {@code 15m}, {@code 1h}, {@code 4h} or {@code 1d}
 * @param openTime the open time of the closed candle, which identifies it
 * @param calculatedAt when the row was computed
 * @param indicators the indicators of that candle
 * @param nearestSupport the nearest swing low below the close, or {@code null}
 * @param nearestResistance the nearest swing high above the close, or {@code null}
 */
public record AnalysisUpdateResponse(
        String market,
        String symbol,
        String timeframe,
        Instant openTime,
        Instant calculatedAt,
        IndicatorsResponse indicators,
        BigDecimal nearestSupport,
        BigDecimal nearestResistance) {}
