package com.cryptopilot.community.service.impl;

import com.cryptopilot.community.dto.response.CoinSentimentResponse;
import com.cryptopilot.community.service.CoinSentimentService;
import com.cryptopilot.community.service.PostService;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Holds the community's leaning per coin, recomputed by a job, so the public market list never waits for the query.
 *
 * <p>Rule: D-125.
 *
 * <p>Reference: Nottingham, M. (2010). RFC 5861, <i>HTTP Cache-Control Extensions for Stale Content</i>.
 */
@Service
public class CoinSentimentServiceImpl implements CoinSentimentService {

    private static final Logger log = LoggerFactory.getLogger(CoinSentimentServiceImpl.class);

    private final PostService posts;
    private final ReentrantLock computing = new ReentrantLock();
    private final AtomicReference<List<CoinSentimentResponse>> latest = new AtomicReference<>();

    public CoinSentimentServiceImpl(PostService posts) {
        this.posts = posts;
    }

    @Override
    public List<CoinSentimentResponse> current() {
        List<CoinSentimentResponse> answer = latest.get();
        if (answer != null) {
            return answer;
        }
        computing.lock();
        try {
            answer = latest.get();
            if (answer == null) {
                answer = posts.coinSentiment();
                latest.set(answer);
            }
            return answer;
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
            latest.set(posts.coinSentiment());
        } catch (RuntimeException failure) {
            log.warn("Coin sentiment: recompute failed, the previous answer stays ({})", failure.toString());
        } finally {
            computing.unlock();
        }
    }
}
