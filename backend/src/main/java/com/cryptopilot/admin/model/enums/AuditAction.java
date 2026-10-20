package com.cryptopilot.admin.model.enums;

/**
 * What an audit entry records, stored by name in {@code audit_log.action_code}. The column has no CHECK, so the task
 * that wires a new kind of command adds its value here without a migration.
 *
 * <p>Rule: BR-57 (account status and role changes, moderation decisions, configuration changes), UC-42, UC-43.
 */
public enum AuditAction {
    ACCOUNT_LOCKED,
    ACCOUNT_UNLOCKED,
    ACCOUNT_BANNED,
    ROLE_CHANGED,
    POST_HIDDEN,
    POST_RESTORED,

    /** A moderator removed a reported post (UC-43, REMOVE_POST, D-99). */
    POST_REMOVED,
    REPORTS_DISMISSED,

    /** A moderator warned the author of a reported post (UC-43, WARN_USER). */
    USER_WARNED,
    /** A configuration row added: a pair, a leverage bracket, a package, a news source or an AI configuration. */
    CONFIGURATION_CREATED,
    /** A configuration row or a system setting changed. */
    CONFIGURATION_CHANGED,
    CONFIGURATION_DELETED
}
