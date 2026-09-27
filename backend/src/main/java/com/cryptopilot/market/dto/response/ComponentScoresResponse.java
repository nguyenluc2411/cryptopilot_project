package com.cryptopilot.market.dto.response;

/**
 * The component scores of one closed candle, 0–100 at scale 2, with the version of their formulas. Spot and Futures
 * are two shapes because Spot has no derivatives component at all, not an empty one.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4; D-53.
 */
public sealed interface ComponentScoresResponse permits SpotComponentsResponse, FuturesComponentsResponse {}
