package com.cryptopilot.admin.model.enums;

/**
 * The kind of row an audit entry is about, stored by name in {@code audit_log.entity_type}; each value names the table
 * the row lives in. Like {@link AuditAction}, a task that audits a new kind of row adds it here.
 *
 * <p>Rule: BR-57, SRS 3.11.9 (the log is filtered by entity type).
 */
public enum AuditedEntity {
    USER_ACCOUNT,
    FORUM_POST,
    CRYPTO_PAIR,
    LEVERAGE_BRACKET,
    SUBSCRIPTION_PACKAGE,
    SUBSCRIPTION_ORDER,
    NEWS_SOURCE,
    AI_CONFIGURATION,
    SYSTEM_SETTING,

    /** A lesson of the Academy (T-114). */
    ACADEMY_ARTICLE,

    /** A term of the Academy glossary (T-114). */
    ACADEMY_TERM,

    /** A topic of the Academy (T-114). */
    ACADEMY_TOPIC
}
