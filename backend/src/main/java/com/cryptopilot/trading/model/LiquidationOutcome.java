package com.cryptopilot.trading.model;

/**
 * The result of the margin and liquidation calculation of a Futures plan: the estimate, or the values that
 * prevented it (a leverage above the bracket's maximum, a notional above the largest bracket).
 *
 * <p>Rule: BR-26, BR-27.
 */
public sealed interface LiquidationOutcome permits LiquidationEstimate, RiskInputRejected {}
