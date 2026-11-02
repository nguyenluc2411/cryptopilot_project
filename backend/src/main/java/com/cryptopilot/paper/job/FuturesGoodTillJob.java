package com.cryptopilot.paper.job;

import com.cryptopilot.paper.config.PaperMatchingProperties;
import com.cryptopilot.paper.service.FuturesOrderService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Every minute, expires the working GTD orders of paper Futures whose date has come (TR-44, plan 7.4): an order is
 * EXPIRED at most a minute after its {@code goodTillDate}. One order failing is left for the next run.
 *
 * <p>Rule: TR-44.
 */
@Component
public class FuturesGoodTillJob {

    /** How often the due orders are looked for. */
    static final Duration INTERVAL = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(FuturesGoodTillJob.class);

    private final FuturesOrderService orders;
    private final TaskScheduler scheduler;
    private final PaperMatchingProperties matching;
    private final Clock clock;

    public FuturesGoodTillJob(
            FuturesOrderService orders, TaskScheduler scheduler, PaperMatchingProperties matching, Clock clock) {
        this.orders = orders;
        this.scheduler = scheduler;
        this.matching = matching;
        this.clock = clock;
    }

    /** Registers the runs while the matching engine is enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!matching.enabled()) {
            return;
        }
        scheduler.scheduleWithFixedDelay(this::run, INTERVAL);
        log.info("TR-44 GTD Futures orders expired every {}", INTERVAL);
    }

    /** One run: every due order, earliest first. */
    void run() {
        Instant at = clock.instant();
        int expired = 0;
        for (UUID orderId : orders.dueGoodTill(at)) {
            try {
                if (orders.expireGoodTill(orderId, at)) {
                    expired++;
                }
            } catch (RuntimeException failure) {
                log.warn("TR-44 GTD order {} not expired now: {}", orderId, failure.toString());
            }
        }
        if (expired > 0) {
            log.info("TR-44 {} GTD Futures orders expired", expired);
        }
    }
}
