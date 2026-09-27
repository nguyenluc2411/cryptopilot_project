package com.cryptopilot.trading.model;

/**
 * The result of a risk calculation: the sized position, or the values that prevented sizing it.
 *
 * <p>Rule: BR-23 to BR-25, BR-30; SRS 3.5.1.
 */
public sealed interface RiskOutcome permits RiskCalculation, RiskInputRejected {}
