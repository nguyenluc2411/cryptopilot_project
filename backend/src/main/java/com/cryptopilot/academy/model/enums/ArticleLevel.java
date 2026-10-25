package com.cryptopilot.academy.model.enums;

/**
 * How much a reader should already know before a lesson of the Academy.
 *
 * <p>Rule: T-114.
 */
public enum ArticleLevel {

    /** No prior knowledge needed. */
    BEGINNER,

    /** Builds on the beginner lessons. */
    INTERMEDIATE,

    /** For readers who already trade. */
    ADVANCED
}
