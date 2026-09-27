package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;

/**
 * The four Spot components, read on the LONG side (BR-21); a component that could not be computed is {@code null}.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4; D-53.
 *
 * @param formulaVersion the version of the component formulas, or {@code null} when nothing is stored
 * @param trend the trend component
 * @param momentum the momentum component
 * @param volume the volume component
 * @param level the level component
 */
public record SpotComponentsResponse(
        String formulaVersion, BigDecimal trend, BigDecimal momentum, BigDecimal volume, BigDecimal level)
        implements ComponentScoresResponse {}
