package com.cryptopilot.market.model;

/**
 * The winning side of the Futures trend component; it accompanies a setup score and is never part of it.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4; D-53.
 */
public enum DominantSide {
    LONG,
    SHORT,
    NEUTRAL
}
