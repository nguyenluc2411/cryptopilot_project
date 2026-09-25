package com.cryptopilot.market.job;

import com.cryptopilot.market.client.BinanceStreamProperties;
import com.cryptopilot.market.model.SnapshotRun;
import com.cryptopilot.market.service.MarketSnapshotService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Writes NSF-03's periodic snapshots every {@code snapshotInterval} (a minute), starting at the next whole
 * minute, whenever the streams are enabled. A failed run is logged and the next one runs as planned; nothing
 * is retried, because a snapshot a minute late is a different snapshot.
 *
 * <p>Rule: NSF-03 (PERIODIC snapshots every minute); TECHNICAL_DESIGN 7.1 step 7 and 10.
 */
@Component
public class MarketSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(MarketSnapshotJob.class);

    private final MarketSnapshotService snapshots;
    private final TaskScheduler scheduler;
    private final BinanceStreamProperties properties;
    private final Clock clock;

    public MarketSnapshotJob(
            MarketSnapshotService snapshots, TaskScheduler scheduler, BinanceStreamProperties properties, Clock clock) {
        this.snapshots = snapshots;
        this.scheduler = scheduler;
        this.properties = properties;
        this.clock = clock;
    }

    /** Schedules the snapshots when the streams are enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            return;
        }
        Instant first = clock.instant().truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES);
        scheduler.scheduleAtFixedRate(this::run, first, properties.snapshotInterval());
        log.info("NSF-03 snapshots every {} from {}", properties.snapshotInterval(), first);
    }

    /** One run; empty when it failed. */
    public Optional<SnapshotRun> run() {
        try {
            SnapshotRun run = snapshots.writePeriodic();
            log.debug(
                    "NSF-03 snapshot {}: {} spot, {} futures, {} stale",
                    run.at(),
                    run.spotRows(),
                    run.futuresRows(),
                    run.stale());
            return Optional.of(run);
        } catch (RuntimeException failure) {
            log.error("NSF-03 snapshot failed; the next one runs as planned", failure);
            return Optional.empty();
        }
    }
}
