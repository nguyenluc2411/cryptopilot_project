package com.cryptopilot.admin.model.enums;

/**
 * Where a system log entry comes from, stored by name in {@code system_event.source} (V59 CHECK). The first two are the
 * application's own; the others are the external services the health panel of SCR-44 reports on.
 *
 * <p>Rule: UC-46.
 */
public enum SystemEventSource {
    /** A request the application could not answer (HTTP 500). */
    APPLICATION(false),
    /** A {@code @Scheduled} job that threw. */
    SCHEDULED_JOB(false),
    /** The Binance Spot and Futures REST API (NSF-01 to NSF-04). */
    BINANCE_REST(true),
    /** The AI Service (D-04). */
    AI_SERVICE(true),
    /** Cloudinary, the media storage of the forum. */
    MEDIA_STORAGE(true),
    /** The RSS feeds and pages of the news sources (NSF-10). */
    NEWS_SOURCE(true),
    /** The SMTP server that sends the mail. */
    MAIL(true);

    private final boolean external;

    SystemEventSource(boolean external) {
        this.external = external;
    }

    /** Whether this is a service outside the application, one row of the health panel. */
    public boolean external() {
        return external;
    }
}
