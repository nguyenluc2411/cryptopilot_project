package com.cryptopilot.community.service.impl;

import com.cryptopilot.community.config.CoinSentimentProperties;
import com.cryptopilot.community.dto.response.CoinSentimentResponse;
import com.cryptopilot.community.service.CoinSentimentService;
import com.cryptopilot.community.service.PostService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Holds the community's leaning per coin, recomputed by a job, so the public market list never waits for the query.
 *
 * <p>With the job off, a request recomputes an answer older than {@code refresh-interval}, one at a time, the others
 * reading the current answer meanwhile. A failed recompute keeps the previous answer.
 *
 * <p>Rule: D-125.
 *
 * <p>Reference: Nottingham, M. (2010). RFC 5861, <i>HTTP Cache-Control Extensions for Stale Content</i> (the last good
 * answer served, its age deciding when it is made again).
 */
@Service
public class CoinSentimentServiceImpl implements CoinSentimentService {

    private static final Logger log = LoggerFactory.getLogger(CoinSentimentServiceImpl.class);

    private final PostService posts;
    private final CoinSentimentProperties properties;
    private final Clock clock;
    private final ReentrantLock computing = new ReentrantLock();
    private final AtomicReference<Computed> latest = new AtomicReference<>();

    public CoinSentimentServiceImpl(PostService posts, CoinSentimentProperties properties, Clock clock) {
        this.posts = posts;
        this.properties = properties;
        this.clock = clock;
    }

    /** One answer and when it was computed. */
    private record Computed(List<CoinSentimentResponse> answer, Instant at) {}

    @Override
    public List<CoinSentimentResponse> current() {
        Computed current = latest.get();
        if (current != null && !stale(current)) {
            return current.answer();
        }
        if (current != null) {
            // Stale and no job: one request recomputes, the others read the current answer meanwhile.
            if (!computing.tryLock()) {
                return current.answer();
            }
        } else {
            computing.lock();
        }
        try {
            Computed known = latest.get();
            if (known != null && !stale(known)) {
                return known.answer();
            }
            try {
                return compute().answer();
            } catch (RuntimeException failure) {
                if (known == null) {
                    throw failure;
                }
                log.warn("Coin sentiment: recompute failed, the previous answer stays ({})", failure.toString());
                return known.answer();
            }
        } finally {
            computing.unlock();
        }
    }

    @Override
    public void refresh() {
        if (!computing.tryLock()) {
            return;
        }
        try {
            compute();
        } catch (RuntimeException failure) {
            log.warn("Coin sentiment: recompute failed, the previous answer stays ({})", failure.toString());
        } finally {
            computing.unlock();
        }
    }

    private Computed compute() {
        Instant at = clock.instant();
        Computed computed = new Computed(posts.coinSentiment(), at);
        latest.set(computed);
        return computed;
    }

    /** Whether a request must recompute this answer: only without the job, once it is older than the interval. */
    private boolean stale(Computed computed) {
        return !properties.enabled() && clock.instant().isAfter(computed.at().plus(properties.refreshInterval()));
    }
}
