package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;

/**
 * The five Futures components, the directional ones read as the better of LONG and SHORT; a component that could not
 * be computed is {@code null}.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4; D-53.
 *
 * @param formulaVersion the version of the component formulas, or {@code null} when nothing is stored
 * @param trend the trend component
 * @param momentum the momentum component
 * @param volume the volume component
 * @param level the level component
 * @param derivatives the derivatives component (funding rate and open interest change)
 */
public record FuturesComponentsResponse(
        String formulaVersion,
        BigDecimal trend,
        BigDecimal momentum,
        BigDecimal volume,
        BigDecimal level,
        BigDecimal derivatives)
        implements ComponentScoresResponse {}
