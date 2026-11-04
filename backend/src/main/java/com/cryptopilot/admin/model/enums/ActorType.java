package com.cryptopilot.admin.model.enums;

/**
 * Who recorded an audit entry, as the SCR-44 filter offers it.
 *
 * <p>Rule: SRS 3.11.9 (filter by actor).
 */
public enum ActorType {
    /** An account acted. */
    USER,
    /** The system acted: a scheduled job or a payment callback ({@code user_id} is null). */
    SYSTEM
}
