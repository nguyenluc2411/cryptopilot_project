package com.cryptopilot.admin.model.enums;

/**
 * Where a system log entry comes from, stored by name in {@code system_event.source} (V59 CHECK). The first two are the
 * application's own; the others are the external services the health panel of SCR-44 reports on.
 *
 * <p>Rule: UC-52.
 */
public enum SystemEventSource {
    /** A request the application could not answer (HTTP 500). */
    APPLICATION(false, false),
    /** A {@code @Scheduled} job that threw. */
    SCHEDULED_JOB(false, false),
    /** The Binance Spot and Futures REST API (NSF-01 to NSF-04). */
    BINANCE_REST(true, false),
    /** The AI Service (D-04). */
    AI_SERVICE(true, false),
    /** Cloudinary, the media storage of the forum. */
    MEDIA_STORAGE(true, false),
    /** The RSS feeds and pages of the news sources (NSF-10): many independent sites, one row. */
    NEWS_SOURCE(true, true),
    /** The SMTP server that sends the mail. */
    MAIL(true, false);

    private final boolean external;
    private final boolean manyEndpoints;

    SystemEventSource(boolean external, boolean manyEndpoints) {
        this.external = external;
        this.manyEndpoints = manyEndpoints;
    }

    /**
     * Whether the row stands for many independent endpoints — the news sites — so one of them failing says little
     * about the others, and only none answering is DOWN.
     */
    public boolean manyEndpoints() {
        return manyEndpoints;
    }

    /** Whether this is a service outside the application, one row of the health panel. */
    public boolean external() {
        return external;
    }
}
