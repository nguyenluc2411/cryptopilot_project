package com.cryptopilot.watchlist.calculator;

import com.cryptopilot.watchlist.model.PriceAlert;
import com.cryptopilot.watchlist.model.PriceAlertHit;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The ACTIVE PRICE alerts of one pair on one market, indexed by threshold, and the prices they are compared with.
 *
 * <p>One ordered map per condition gives every alert a price meets in {@code O(log n + k)}:
 *
 * <ul>
 *   <li>CROSS_ABOVE: thresholds in {@code (p₀, p₁]} when the price rose, so a jump over several fires each of them.
 *   <li>CROSS_BELOW: thresholds in {@code [p₁, p₀)} when it fell.
 *   <li>GREATER_THAN / LESS_THAN: every threshold below / above {@code p₁}, on every evaluation while it holds; the
 *       trigger mode and the cooldown decide how often it fires (D-88).
 * </ul>
 *
 * <p>{@code p₀} is the last price evaluated. Without one (just loaded, or forgotten after a reconnection) a cross
 * cannot be told and does not fire; a level still does. A price stamped at or before the last one accepted is
 * dropped, so a late or repeated update never moves {@code p₀} back. A pair is evaluated at most once per
 * {@code throttle}, on the latest price received (SRS 3.4.4); prices in between are kept only as the latest.
 *
 * <p>Thread-safe: the engine's partition thread evaluates while listener threads add and remove alerts. An alert only
 * moves forward to a newer row version: a stale read that arrives late is ignored (see {@link #put}).
 *
 * <p>Rule: NSF-06, BR-18, BR-19, BR-20; SRS 3.4.4; TECHNICAL_DESIGN 7.9.
 *
 * <p>Reference: Cormen, T. H., Leiserson, C. E., Rivest, R. L. &amp; Stein, C. (2022). <i>Introduction to
 * Algorithms</i> (4th ed.). MIT Press, ch. 12–13 (ordered search trees: a range of keys in {@code O(log n + k)}).
 * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 8 (unreliable clocks
 * and delayed messages: order by the event's own timestamp, and do not trust state that may be stale).
 */
public final class PriceAlertBook {

    private final Clock clock;
    private final Duration throttle;
    private final Duration tombstoneTtl;

    private final NavigableMap<BigDecimal, Map<UUID, PriceAlert>> crossAbove = new TreeMap<>();
    private final NavigableMap<BigDecimal, Map<UUID, PriceAlert>> crossBelow = new TreeMap<>();
    private final NavigableMap<BigDecimal, Map<UUID, PriceAlert>> greaterThan = new TreeMap<>();
    private final NavigableMap<BigDecimal, Map<UUID, PriceAlert>> lessThan = new TreeMap<>();
    private final Map<UUID, PriceAlert> byId = new HashMap<>();

    /** The version each alert was removed at, oldest first, kept for {@code tombstoneTtl}. */
    private final LinkedHashMap<UUID, Tombstone> removed = new LinkedHashMap<>();

    /** The last price evaluated, and when the exchange produced it; {@code null} until there is one. */
    private BigDecimal previous;

    private Instant previousAt;

    /** The latest price received since the last evaluation; {@code null} when there is none. */
    private BigDecimal latest;

    private Instant latestAt;

    /** When the last evaluation ran, by the engine's clock. */
    private Instant evaluatedAt;

    /**
     * @param tombstoneTtl how long a removed alert's version blocks a stale read of it; a read in flight takes far
     *     less, and the bound keeps the memory to the alerts removed within it
     */
    public PriceAlertBook(Clock clock, Duration throttle, Duration tombstoneTtl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.throttle = Objects.requireNonNull(throttle, "throttle");
        this.tombstoneTtl = Objects.requireNonNull(tombstoneTtl, "tombstoneTtl");
    }

    /**
     * Adds the alert, or replaces the one with its key, only if it is newer: a version not above the one held, or
     * the one it was removed at, is a stale read and is ignored, so the book only moves forward.
     *
     * <p>Reference: Goetz, B. et al. (2006). <i>Java Concurrency in Practice</i>. Addison-Wesley, §2.3 (the
     * check-then-act is one atomic action). Lamport, L. (1978). Time, Clocks, and the Ordering of Events in a
     * Distributed System. <i>Communications of the ACM</i>, 21(7) (a monotonic number orders the states).
     *
     * @return whether the alert was taken
     */
    public synchronized boolean put(PriceAlert alert) {
        PriceAlert held = byId.get(alert.alertId());
        Tombstone gone = removed.get(alert.alertId());
        if ((held != null && held.version() >= alert.version())
                || (gone != null && gone.version() >= alert.version())) {
            return false;
        }
        removed.remove(alert.alertId());
        unindex(alert.alertId());
        byId.put(alert.alertId(), alert);
        mapOf(alert)
                .computeIfAbsent(alert.threshold(), ignored -> new LinkedHashMap<>())
                .put(alert.alertId(), alert);
        return true;
    }

    /**
     * Removes the alert and remembers the version it held, so a stale read of that version cannot put it back
     * ({@link #put}).
     *
     * @return whether it was here
     */
    public synchronized boolean remove(UUID alertId) {
        PriceAlert held = unindex(alertId);
        if (held == null) {
            return false;
        }
        bury(alertId, held.version());
        return true;
    }

    /**
     * Removes the alert only if the version held is not above {@code version}: a newer rule put meanwhile stays.
     *
     * @return whether it was removed
     */
    public synchronized boolean removeIfVersionAtMost(UUID alertId, long version) {
        PriceAlert held = byId.get(alertId);
        return held != null && held.version() <= version && remove(alertId);
    }

    /** The version of the alert held, if any. */
    public synchronized OptionalLong versionOf(UUID alertId) {
        PriceAlert held = byId.get(alertId);
        return held == null ? OptionalLong.empty() : OptionalLong.of(held.version());
    }

    public synchronized boolean contains(UUID alertId) {
        return byId.containsKey(alertId);
    }

    public synchronized int size() {
        return byId.size();
    }

    /** The alerts held, as they stand. */
    public synchronized Collection<PriceAlert> alerts() {
        return List.copyOf(byId.values());
    }

    /**
     * Keeps a price for the next evaluation, unless it is not newer than the last price accepted.
     *
     * @return whether the price was kept
     */
    public synchronized boolean offer(BigDecimal price, Instant at) {
        Instant newest = latestAt != null ? latestAt : previousAt;
        if (newest != null && !at.isAfter(newest)) {
            return false;
        }
        latest = price;
        latestAt = at;
        return true;
    }

    /** Whether a price is waiting for its evaluation. */
    public synchronized boolean hasPending() {
        return latest != null;
    }

    /**
     * Evaluates the latest price, if one is waiting and the throttle allows it now; otherwise leaves it waiting.
     *
     * @return the alerts the price meets and their trigger mode lets fire, in threshold order
     */
    public synchronized List<PriceAlertHit> evaluate() {
        Instant now = clock.instant();
        if (latest == null || (evaluatedAt != null && now.isBefore(evaluatedAt.plus(throttle)))) {
            return List.of();
        }
        BigDecimal p0 = previous;
        BigDecimal p1 = latest;
        Instant bar = TriggerPolicy.barOpenTime(latestAt);
        previous = p1;
        previousAt = latestAt;
        latest = null;
        latestAt = null;
        evaluatedAt = now;

        List<PriceAlert> met = new ArrayList<>();
        if (p0 != null && p1.compareTo(p0) > 0) {
            crossAbove.subMap(p0, false, p1, true).values().forEach(at -> met.addAll(at.values()));
        }
        if (p0 != null && p1.compareTo(p0) < 0) {
            crossBelow.subMap(p1, true, p0, false).descendingMap().values().forEach(at -> met.addAll(at.values()));
        }
        greaterThan.headMap(p1, false).values().forEach(at -> met.addAll(at.values()));
        lessThan.tailMap(p1, false).descendingMap().values().forEach(at -> met.addAll(at.values()));

        List<PriceAlertHit> hits = new ArrayList<>();
        for (PriceAlert alert : met) {
            boolean expired = alert.expiresAt() != null && !now.isBefore(alert.expiresAt());
            if (!expired && TriggerPolicy.allows(alert, now, bar)) {
                hits.add(new PriceAlertHit(alert, p1, bar, now));
            }
        }
        return hits;
    }

    /**
     * Records a trigger the database accepted: a ONCE alert is TRIGGERED and leaves the book; the others keep the time
     * and candle of this trigger for their next check.
     */
    public synchronized void triggered(PriceAlertHit hit) {
        PriceAlert alert = byId.get(hit.alert().alertId());
        // A newer row read meanwhile already carries the stored trigger.
        if (alert == null || alert.version() != hit.alert().version()) {
            return;
        }
        switch (alert.triggerMode()) {
            case ONCE -> remove(alert.alertId());
            case ONCE_PER_BAR, EVERY_TIME -> put(alert.triggeredAt(hit.at(), hit.barOpenTime()));
        }
    }

    /**
     * Forgets the previous price when the exchange produced it before {@code cutoff}: after a reconnection it may be
     * stale, and a cross measured from it would be a guess.
     */
    public synchronized void forgetPreviousBefore(Instant cutoff) {
        if (previousAt != null && previousAt.isBefore(cutoff)) {
            previous = null;
            previousAt = null;
        }
    }

    private PriceAlert unindex(UUID alertId) {
        PriceAlert held = byId.remove(alertId);
        if (held != null) {
            NavigableMap<BigDecimal, Map<UUID, PriceAlert>> map = mapOf(held);
            Map<UUID, PriceAlert> atThreshold = map.get(held.threshold());
            atThreshold.remove(alertId);
            if (atThreshold.isEmpty()) {
                map.remove(held.threshold());
            }
        }
        return held;
    }

    private void bury(UUID alertId, long version) {
        Instant now = clock.instant();
        // Re-inserted at the end, so the map stays oldest first and the expired ones are at its head.
        removed.remove(alertId);
        removed.put(alertId, new Tombstone(version, now));
        Instant cutoff = now.minus(tombstoneTtl);
        for (Iterator<Tombstone> it = removed.values().iterator();
                it.hasNext() && it.next().at().isBefore(cutoff); ) {
            it.remove();
        }
    }

    private NavigableMap<BigDecimal, Map<UUID, PriceAlert>> mapOf(PriceAlert alert) {
        return switch (alert.condition()) {
            case CROSS_ABOVE -> crossAbove;
            case CROSS_BELOW -> crossBelow;
            case GREATER_THAN -> greaterThan;
            case LESS_THAN -> lessThan;
        };
    }

    private record Tombstone(long version, Instant at) {}
}
