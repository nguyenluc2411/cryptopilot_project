package com.cryptopilot.admin.service.impl;

import com.cryptopilot.admin.model.enums.SystemEventSource;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * When each external service last answered, as this instance saw it. A success is far more frequent than a failure and
 * tells nothing worth keeping, so it is held in memory rather than written: after a restart a service reads UNKNOWN
 * until it is called again, which is what the panel then says.
 *
 * <p>Rule: UC-52 ("views the status of the external APIs").
 */
@Component
public class ExternalCallTracker {

    private final Map<SystemEventSource, Instant> lastSuccess = new ConcurrentHashMap<>();
    private final Clock clock;

    ExternalCallTracker(Clock clock) {
        this.clock = clock;
    }

    /** A call to this service answered. */
    public void succeeded(SystemEventSource source) {
        lastSuccess.put(source, clock.instant());
    }

    /** When this service last answered, or empty when not since the start. */
    public Optional<Instant> lastSuccess(SystemEventSource source) {
        return Optional.ofNullable(lastSuccess.get(source));
    }

    /** A copy of every service's last answer. */
    public Map<SystemEventSource, Instant> snapshot() {
        Map<SystemEventSource, Instant> copy = new EnumMap<>(SystemEventSource.class);
        copy.putAll(lastSuccess);
        return copy;
    }
}
