package com.cryptopilot.market.dto.response;

/**
 * A setup score read at request time from stored components under one style preset. Only a Futures score carries a
 * dominant side, so Spot and Futures are two shapes.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4; D-53.
 */
public sealed interface SetupScoreResponse permits SpotSetupScoreResponse, FuturesSetupScoreResponse {}
