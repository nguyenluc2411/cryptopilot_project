package com.cryptopilot.user.model;

/**
 * The two groups of the risk questionnaire (SRS 3.2.5, D-53).
 *
 * <p>Rule: BR-66; SRS 3.2.5.
 */
public enum QuestionGroup {

    /** How much loss the Trader's finances can bear. */
    CAPACITY,

    /** How the Trader feels about risk and reacts to losses. */
    ATTITUDE
}
