package com.cryptopilot.market.job;

import com.cryptopilot.market.client.BinanceStreamProperties;
import com.cryptopilot.market.client.fallback.OkxProperties;
import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.DisplayPriceService;
import com.cryptopilot.market.service.MarketHealthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Reads the display-only fallback prices of each market whose exchange data is lost every {@code poll-interval}
 * (D-158) and pushes them to {@code /topic/market.fallback.<MARKET>}; forgets them once its data is no longer lost (live,
 * under maintenance, or recovering from a maintenance), when the exchange's own prices are shown.
 *
 * <p>Runs only while the fallback, the health monitor and the streams are all enabled: without the monitor no market
 * is ever lost. Orchestration only.
 *
 * <p>Rule: BR-09; NSF-03; D-158.
 */
@Component
public class FallbackPriceJob {

    /** The topic of a market's fallback prices, followed by the market. */
    public static final String TOPIC = "/topic/market.fallback.";

    private static final Logger log = LoggerFactory.getLogger(FallbackPriceJob.class);

    private final DisplayPriceService prices;
    private final MarketHealthService health;
    private final SimpMessageSendingOperations broker;
    private final TaskScheduler scheduler;
    private final OkxProperties fallback;
    private final MarketHealthProperties monitor;
    private final BinanceStreamProperties streams;

    public FallbackPriceJob(
            DisplayPriceService prices,
            MarketHealthService health,
            SimpMessageSendingOperations broker,
            TaskScheduler scheduler,
            OkxProperties fallback,
            MarketHealthProperties monitor,
            BinanceStreamProperties streams) {
        this.prices = prices;
        this.health = health;
        this.broker = broker;
        this.scheduler = scheduler;
        this.fallback = fallback;
        this.monitor = monitor;
        this.streams = streams;
    }

    /** Schedules the polls, when enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!fallback.enabled() || !monitor.enabled() || !streams.enabled()) {
            return;
        }
        scheduler.scheduleWithFixedDelay(this::poll, fallback.pollInterval());
        log.info("D-158 fallback prices of a lost market read every {}", fallback.pollInterval());
    }

    /** One poll of every market; one that fails does not stop the other. */
    void poll() {
        for (MarketType market : MarketType.values()) {
            try {
                if (health.dataLost(market)) {
                    prices.refresh(market);
                    broker.convertAndSend(TOPIC + market.name(), prices.prices(market));
                } else {
                    prices.clear(market);
                }
            } catch (RuntimeException failure) {
                log.warn("D-158 {} fallback prices not polled: {}", market, failure.toString());
            }
        }
    }
}
