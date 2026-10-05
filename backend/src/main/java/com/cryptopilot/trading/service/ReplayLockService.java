package com.cryptopilot.trading.service;

import com.cryptopilot.market.model.enums.MarketType;
import java.util.UUID;

/**
 * The running state and lock of a pair's restart replay: one Redis key per pair, {@code rpl:{market}:{pairId}}, set
 * only if absent with a TTL, renewed by a heartbeat while the replay runs, deleted when it ends. A holder that crashes
 * leaves the key to expire.
 *
 * <p>Rule: NSF-07; D-79, D-85.
 *
 * <p>Reference: Kleppmann, M. (2016). How to do distributed locking.
 */
public interface ReplayLockService {

    /** Takes the pair's key for this instance; true when taken now or already held by it, false when another holds it. */
    boolean tryAcquire(MarketType market, UUID pairId);

    /** Deletes the pair's key when this instance holds it; a key another instance holds is left alone. */
    void release(MarketType market, UUID pairId);

    /** Releases every key this instance holds; used on shutdown. */
    void releaseAll();

    /**
     * Whether any instance's replay of the pair is running: the D-79 gate. When Redis cannot be read, the answer is
     * {@code true}, so a caller that must not act during a replay holds back rather than guesses.
     */
    boolean isReplaying(MarketType market, UUID pairId);

    /** Renews the TTL of every key this instance holds; a key no longer its own is dropped. Answers how many renewed. */
    int heartbeat();
}
