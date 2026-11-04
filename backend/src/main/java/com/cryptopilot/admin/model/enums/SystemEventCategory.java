package com.cryptopilot.admin.model.enums;

/**
 * What kind of failure a system log entry records, stored by name in {@code system_event.category} (V59 CHECK).
 *
 * <p>Rule: UC-52; registration form, Admin System ("system errors, and external API failures").
 */
public enum SystemEventCategory {
    /** The application failed on its own: an unexpected error answered with 500, or a scheduled job that threw. */
    SYSTEM_ERROR,
    /** A call to a service outside the application failed. */
    EXTERNAL_API_FAILURE
}
