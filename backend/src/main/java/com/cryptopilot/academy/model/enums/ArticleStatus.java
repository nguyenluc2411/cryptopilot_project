package com.cryptopilot.academy.model.enums;

/**
 * Where a lesson of the Academy stands. Only {@link #PUBLISHED} lessons are shown outside the administration console.
 *
 * <p>Rule: T-114.
 */
public enum ArticleStatus {

    /** Being written; shown to administrators only. */
    DRAFT,

    /** Shown to everyone, a Guest included. */
    PUBLISHED,

    /** Taken down by an administrator; kept, so it can be published again. */
    HIDDEN
}
