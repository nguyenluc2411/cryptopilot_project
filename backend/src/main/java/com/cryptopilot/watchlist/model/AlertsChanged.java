package com.cryptopilot.watchlist.model;

import java.util.Set;
import java.util.UUID;

/**
 * These alerts were created, edited, paused, resumed, deleted or expired. Internal to the watchlist module: the alert
 * engine reads each one again after the commit and adds, replaces or removes it in its books.
 *
 * <p>Rule: NSF-06; TECHNICAL_DESIGN 7.9.
 *
 * @param alertIds the alerts whose stored state changed
 */
public record AlertsChanged(Set<UUID> alertIds) {

    public AlertsChanged {
        alertIds = Set.copyOf(alertIds);
    }

    public static AlertsChanged of(UUID alertId) {
        return new AlertsChanged(Set.of(alertId));
    }
}
