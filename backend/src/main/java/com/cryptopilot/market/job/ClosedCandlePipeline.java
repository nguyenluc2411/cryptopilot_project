package com.cryptopilot.market.job;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.BinanceStreamProperties;
import com.cryptopilot.market.client.StreamMessage.KlineMessage;
import com.cryptopilot.market.service.StreamCandleService;
import com.cryptopilot.market.service.StreamTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Carries closed candles from the stream readers to storage without ever blocking a reader.
 *
 * <p>A fixed number of partitions, each a bounded queue drained by one virtual thread; a pair always lands in
 * the same partition (by market and pair), so the candles of one series are stored — and their gaps judged —
 * in the order the exchange sent them, while different pairs are stored in parallel (TECHNICAL_DESIGN 10).
 *
 * <p>A reader only offers: it never waits for space. A queue can only fill if storage stops for a long time
 * (about 4 closes per pair per 15 minutes arrive); a candle refused then is logged, and nothing is lost for
 * good — the next close of that series finds it missing and reports the gap to NSF-02.
 *
 * <p>A failure storing one candle is logged and the consumer carries on; that series' next close reports the
 * gap the same way.
 *
 * <p>Rule: NSF-03; BR-08; TECHNICAL_DESIGN 7.1 step 2 and 10.
 *
 * <p>Reference: Goetz, B. et al. (2006). <i>Java Concurrency in Practice</i>. Addison-Wesley, ch. 5.3
 * (producer–consumer with bounded blocking queues). JEP 444 (2023). <i>Virtual Threads</i>. OpenJDK.
 */
@Component
public class ClosedCandlePipeline {

    private static final Logger log = LoggerFactory.getLogger(ClosedCandlePipeline.class);

    private final StreamCandleService candles;
    private final List<BlockingQueue<Work>> partitions = new ArrayList<>();
    private final List<Thread> consumers = new ArrayList<>();

    public ClosedCandlePipeline(StreamCandleService candles, BinanceStreamProperties properties) {
        this.candles = candles;
        for (int i = 0; i < properties.partitions(); i++) {
            partitions.add(new ArrayBlockingQueue<>(properties.queueCapacity()));
        }
    }

    /** Starts one consumer per partition; a second call does nothing. */
    public synchronized void start() {
        if (!consumers.isEmpty()) {
            return;
        }
        for (int i = 0; i < partitions.size(); i++) {
            BlockingQueue<Work> queue = partitions.get(i);
            consumers.add(Thread.ofVirtual().name("market-candles-" + i).start(() -> drain(queue)));
        }
    }

    /** Stops the consumers; candles still queued are left to the next start's gap detection. */
    public synchronized void stop() {
        consumers.forEach(Thread::interrupt);
        consumers.clear();
    }

    /**
     * Queues a closed candle for storage, without waiting.
     *
     * @return whether it was queued
     */
    public boolean submit(MarketType market, StreamTarget target, KlineMessage message) {
        BlockingQueue<Work> queue =
                partitions.get(Math.floorMod(Objects.hash(market, target.pairId()), partitions.size()));
        if (queue.offer(new Work(market, target, message))) {
            return true;
        }
        log.error(
                "NSF-03 {} {} {} candle {} refused: its partition is full; the next close reports the gap",
                market,
                target.symbol(),
                message.interval().code(),
                message.kline().openTime());
        return false;
    }

    private void drain(BlockingQueue<Work> queue) {
        while (!Thread.currentThread().isInterrupted()) {
            Work work;
            try {
                work = queue.take();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                candles.onKline(work.market(), work.target(), work.message());
            } catch (RuntimeException failure) {
                log.error(
                        "NSF-03 {} {} {} candle {} not stored; the next close reports the gap",
                        work.market(),
                        work.target().symbol(),
                        work.message().interval().code(),
                        work.message().kline().openTime(),
                        failure);
            }
        }
    }

    /** One closed candle on its way to storage. */
    private record Work(MarketType market, StreamTarget target, KlineMessage message) {}
}
