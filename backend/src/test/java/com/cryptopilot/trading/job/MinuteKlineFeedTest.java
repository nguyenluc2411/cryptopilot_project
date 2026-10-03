package com.cryptopilot.trading.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.config.MatchingProperties;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.service.MatchingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The feed between the {@code kline_1m} stream and the matching engine: what is held, what is replayed and in which
 * order the engine receives it. The engine and the exchange are mocked, and every test waits until no replay is queued
 * or running, so it is deterministic.
 *
 * <p>Rule: NSF-07; TECHNICAL_DESIGN 7.7; A-04; D-78; Q-34.
 */
class MinuteKlineFeedTest {

    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000b001");
    private static final UUID OTHER_PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000b002");
    private static final Instant NOW = Instant.parse("2026-10-03T08:10:30Z");
    private static final Instant M0 = Instant.parse("2026-10-03T08:00:00Z");

    private final MatchingWorker worker = mock(MatchingWorker.class);
    private final MatchingService matching = mock(MatchingService.class);
    private final MarketApi market = mock(MarketApi.class);
    private final List<PriceRange> submitted = new CopyOnWriteArrayList<>();
    private final List<TrackedEntry> entries = new ArrayList<>();
    private final List<Thread> threads = new CopyOnWriteArrayList<>();
    private final ThreadFactory recording = task -> {
        Thread thread = Thread.ofVirtual().unstarted(task);
        threads.add(thread);
        return thread;
    };
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger feedLogger = (Logger) LoggerFactory.getLogger(MinuteKlineFeed.class);

    /** The closed candles the exchange stub serves, at most three a page. */
    private final List<MinuteKline> served = new CopyOnWriteArrayList<>();

    private MinuteKlineFeed feed;

    @BeforeEach
    void setUp() {
        logs.start();
        feedLogger.addAppender(logs);
        when(worker.isRunning()).thenReturn(true);
        doAnswer(call -> submitted.add(call.getArgument(0))).when(worker).submit(any());
        when(market.closedMinuteKlines(any(), any(), any())).thenAnswer(call -> {
            Instant from = call.getArgument(2);
            return MinuteKlineBatch.of(served.stream()
                    .filter(kline -> kline.market() == call.getArgument(0))
                    .filter(kline -> !kline.openTime().isBefore(from))
                    .limit(3)
                    .toList());
        });
    }

    @AfterEach
    void tearDown() {
        feedLogger.detachAppender(logs);
        if (feed != null) {
            feed.stop();
        }
    }

    @Test
    void NSF07_updatesBeforeTheStart_areHeldOneRangePerMinute_andHandedOnInOrderAtTheStart() {
        feed = feed(replay(true, 3, 1440));

        feed.onMinuteKline(update(M0, "100", "101", false, 10));
        feed.onMinuteKline(update(M0, "99", "101", false, 20));
        feed.onMinuteKline(update(M0, "99", "102", true, 60));
        feed.onMinuteKline(update(minute(1), "101", "101", false, 5));
        assertThat(submitted).isEmpty();
        startAndAwaitIdle();

        assertThat(submitted)
                .containsExactly(range(M0, "99", "102", true, 60), range(minute(1), "101", "101", false, 5));
        verify(worker).start();
    }

    @Test
    void NSF07_afterTheStart_aPairWithoutEntries_isLive_andItsUpdatesAreRangesFromTheCandleOpenTime() {
        feed = feed(replay(true, 3, 1440));
        startAndAwaitIdle();

        feed.onMinuteKline(update(M0, "99", "101", false, 42));

        assertThat(submitted).containsExactly(range(M0, "99", "101", false, 42));
        verifyNoInteractions(market);
    }

    @Test
    void A04_aPairWithEntries_isReplayedFromTheCandleAfterItsWatermark_pageByPage_upToTheCurrentMinute() {
        activeEntry(PAIR, minute(-30));
        when(matching.watermark(MarketType.SPOT, PAIR)).thenReturn(Optional.of(M0));
        serveClosed(1, 9);
        feed = feed(replay(true, 3, 1440));

        startAndAwaitIdle();

        assertThat(submitted)
                .extracting(PriceRange::from, PriceRange::closed)
                .containsExactly(
                        tuple(minute(1), true),
                        tuple(minute(2), true),
                        tuple(minute(3), true),
                        tuple(minute(4), true),
                        tuple(minute(5), true),
                        tuple(minute(6), true),
                        tuple(minute(7), true),
                        tuple(minute(8), true),
                        tuple(minute(9), true));
        verify(market).closedMinuteKlines(MarketType.SPOT, PAIR, minute(1));
        verify(market).closedMinuteKlines(MarketType.SPOT, PAIR, minute(4));
        verify(market).closedMinuteKlines(MarketType.SPOT, PAIR, minute(7));
        verify(market).closedMinuteKlines(MarketType.SPOT, PAIR, minute(10));
        assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    void R8_withAWatermarkOlderThanTheFirstActivation_theReplayStartsAtTheActivationMinute() {
        activeEntry(PAIR, minute(5).plusSeconds(40));
        activeEntry(PAIR, minute(3).plusSeconds(10));
        when(matching.watermark(MarketType.SPOT, PAIR)).thenReturn(Optional.of(M0.minus(Duration.ofHours(20))));
        feed = feed(replay(true, 0, 1440));

        startAndAwaitIdle();

        verify(market).closedMinuteKlines(MarketType.SPOT, PAIR, minute(3));
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("replay window"));
    }

    @Test
    void A04_withoutAWatermark_theReplayStartsAtTheMinuteOfTheFirstActivation() {
        activeEntry(PAIR, minute(5).plusSeconds(40));
        activeEntry(PAIR, minute(3).plusSeconds(10));
        when(matching.watermark(MarketType.SPOT, PAIR)).thenReturn(Optional.empty());
        feed = feed(replay(true, 0, 1440));

        startAndAwaitIdle();

        verify(market).closedMinuteKlines(MarketType.SPOT, PAIR, minute(3));
    }

    @Test
    void Q34_aStartOlderThanTheWindow_isMovedToTheWindow_andTheSkippedCandlesAreLogged() {
        activeEntry(PAIR, M0.minus(Duration.ofDays(3)));
        when(matching.watermark(MarketType.SPOT, PAIR)).thenReturn(Optional.of(M0.minus(Duration.ofDays(2))));
        feed = feed(new MatchingProperties.Replay(true, Duration.ofHours(24), Duration.ZERO, 0, 1440));

        startAndAwaitIdle();

        Instant windowStart = minute(10).minus(Duration.ofHours(24));
        verify(market).closedMinuteKlines(MarketType.SPOT, PAIR, windowStart);
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("beyond the replay window")
                    .contains(PAIR.toString());
        });
    }

    /**
     * Live updates arrive while the replay fetches: they are held. The replay covers minute 2, so the held minute 2 is
     * dropped and only minute 3 follows the replayed candles; a late closed update of minute 2 is dropped too.
     */
    @Test
    void A04_liveUpdatesDuringTheReplay_areHeld_andAMinuteTheReplayCoveredIsNotAppliedTwice() {
        activeEntry(PAIR, M0);
        when(matching.watermark(MarketType.SPOT, PAIR)).thenReturn(Optional.of(M0));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, minute(1))).thenAnswer(call -> {
            feed.onMinuteKline(update(minute(2), "90", "95", false, 30));
            feed.onMinuteKline(update(minute(3), "96", "97", false, 5));
            assertThat(submitted).as("held while the replay runs").isEmpty();
            return MinuteKlineBatch.of(List.of(closed(minute(1)), closed(minute(2))));
        });
        feed = feed(replay(true, 3, 1440));

        startAndAwaitIdle();
        feed.onMinuteKline(update(minute(2), "90", "95", true, 60));
        feed.onMinuteKline(update(minute(3), "96", "98", false, 10));

        assertThat(submitted).extracting(PriceRange::from).containsExactly(minute(1), minute(2), minute(3), minute(3));
        assertThat(submitted.get(1).closed())
                .as("minute 2 comes from the replay")
                .isTrue();
    }

    @Test
    void A04_aCandleThatClosesDuringTheReplay_isFetchedAgainBeforeTheHeldUpdatesGoOn() {
        activeEntry(PAIR, M0);
        when(matching.watermark(MarketType.SPOT, PAIR)).thenReturn(Optional.of(M0));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, minute(1))).thenAnswer(call -> {
            feed.onMinuteKline(update(minute(3), "96", "97", false, 5));
            return MinuteKlineBatch.of(List.of(closed(minute(1))));
        });
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, minute(2)))
                .thenReturn(MinuteKlineBatch.of(List.of()))
                .thenReturn(MinuteKlineBatch.of(List.of(closed(minute(2)))));
        feed = feed(replay(true, 3, 1440));

        startAndAwaitIdle();

        assertThat(submitted).extracting(PriceRange::from).containsExactly(minute(1), minute(2), minute(3));
        assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    /** R4: with nothing held, the replay goes on until it has reached the current minute, not the first empty page. */
    @Test
    void R4_withNothingHeld_aCandleTheExchangeServesLate_isStillReplayedBeforeGoingLive() {
        activeEntry(PAIR, M0);
        when(matching.watermark(MarketType.SPOT, PAIR)).thenReturn(Optional.of(minute(7)));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, minute(8)))
                .thenReturn(MinuteKlineBatch.of(List.of(closed(minute(8)))));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, minute(9)))
                .thenReturn(MinuteKlineBatch.of(List.of()))
                .thenReturn(MinuteKlineBatch.of(List.of(closed(minute(9)))));
        feed = feed(replay(true, 3, 1440));

        startAndAwaitIdle();

        assertThat(submitted).extracting(PriceRange::from).containsExactly(minute(8), minute(9));
        assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    void F1_aHoleThatStaysAfterTheCatchUps_isRetried_andReplayedOnceTheExchangeServesIt() {
        activeEntry(PAIR, M0);
        when(matching.watermark(MarketType.SPOT, PAIR)).thenReturn(Optional.of(M0));
        AtomicInteger asked = new AtomicInteger();
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, minute(1))).thenAnswer(call -> {
            if (asked.incrementAndGet() == 1) {
                feed.onMinuteKline(update(minute(5), "96", "97", false, 5));
            }
            return asked.get() <= 3
                    ? MinuteKlineBatch.of(List.of())
                    : MinuteKlineBatch.of(
                            List.of(closed(minute(1)), closed(minute(2)), closed(minute(3)), closed(minute(4))));
        });
        feed = feed(replay(true, 2, 1440), RETRY);

        startAndAwaitIdle();
        awaitSubmitted(5);
        feed.onMinuteKline(update(minute(5), "95", "97", true, 60));
        feed.onMinuteKline(update(minute(6), "96", "97", false, 5));

        assertThat(submitted)
                .extracting(PriceRange::from)
                .containsExactly(minute(1), minute(2), minute(3), minute(4), minute(5), minute(5), minute(6));
        verify(market, times(4)).closedMinuteKlines(MarketType.SPOT, PAIR, minute(1));
        assertThat(errors()).hasSize(1);
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("not matched"));
    }

    /** R1: a stream drop while running sends the pair back to replay the missing minutes, before what follows them. */
    @Test
    void R1_aStreamDropWhileRunning_isReplayed_andNothingAfterTheHoleReachesTheEngineBeforeIt() {
        serveClosed(2, 3);
        feed = feed(replay(true, 3, 1440));
        startAndAwaitIdle();
        feed.onMinuteKline(update(minute(1), "1", "2", false, 30));
        feed.onMinuteKline(update(minute(1), "1", "2", true, 60));

        feed.onMinuteKline(update(minute(4), "1", "2", false, 5));
        awaitIdle();
        feed.onMinuteKline(update(minute(4), "1", "2", true, 60));

        assertThat(submitted)
                .extracting(PriceRange::from, PriceRange::closed)
                .containsExactly(
                        tuple(minute(1), false),
                        tuple(minute(1), true),
                        tuple(minute(2), true),
                        tuple(minute(3), true),
                        tuple(minute(4), false),
                        tuple(minute(4), true));
        verify(market).closedMinuteKlines(MarketType.SPOT, PAIR, minute(2));
    }

    /** R1: a minute whose closed update never comes is replayed once the next minute's updates show it is missing. */
    @Test
    void R1_aMinuteWhoseClosedUpdateNeverCame_isReplayedFromTheExchange() {
        serveClosed(2, 2);
        feed = feed(replay(true, 3, 1440));
        startAndAwaitIdle();
        feed.onMinuteKline(update(minute(1), "1", "2", true, 60));
        feed.onMinuteKline(update(minute(2), "1", "2", false, 30));

        feed.onMinuteKline(update(minute(3), "1", "2", false, 5));
        awaitIdle();

        assertThat(submitted)
                .extracting(PriceRange::from, PriceRange::closed)
                .containsExactly(
                        tuple(minute(1), true),
                        tuple(minute(2), false),
                        tuple(minute(2), true),
                        tuple(minute(3), false));
    }

    /**
     * R1: after a reconnection the stream resumes minutes later; until the exchange serves the hole, no closed candle
     * after it reaches the engine, so the watermark cannot pass the hole and a restart replays it.
     */
    @Test
    void R1_afterAReconnection_nothingFollowsTheHoleUntilItIsReplayed_soARestartReplaysIt() {
        feed = feed(replay(true, 3, 1440));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, minute(2)))
                .thenReturn(MinuteKlineBatch.refusedUntil(NOW.plus(Duration.ofHours(1))));
        startAndAwaitIdle();
        feed.onMinuteKline(update(minute(1), "1", "2", true, 60));

        feed.onMinuteKline(update(minute(6), "1", "2", false, 5));
        feed.onMinuteKline(update(minute(6), "1", "2", true, 60));
        feed.onMinuteKline(update(minute(7), "1", "2", false, 5));
        feed.stop();

        assertThat(submitted).extracting(PriceRange::from).containsExactly(minute(1));
    }

    @Test
    void R1_withTheReplayOff_aHoleIsOnlyLogged_andTheUpdatesGoOn() {
        feed = feed(replay(false, 3, 1440));
        startAndAwaitIdle();

        feed.onMinuteKline(update(minute(1), "1", "2", true, 60));
        feed.onMinuteKline(update(minute(4), "1", "2", false, 1));
        feed.onMinuteKline(update(minute(4), "1", "2", true, 60));
        feed.onMinuteKline(update(minute(5), "1", "2", false, 1));

        assertThat(submitted).extracting(PriceRange::from).containsExactly(minute(1), minute(4), minute(4), minute(5));
        assertThat(logs.list)
                .filteredOn(event -> event.getFormattedMessage().contains("no candles from"))
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains("no candles from " + minute(2) + " to " + minute(4)));
        verifyNoInteractions(market);
    }

    @Test
    void A04_aRefusalOfTheExchange_isAskedAgainAtItsTime() {
        activeEntry(PAIR, M0);
        when(matching.watermark(MarketType.SPOT, PAIR)).thenReturn(Optional.of(minute(8)));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, minute(9)))
                .thenReturn(MinuteKlineBatch.refusedUntil(NOW.minusSeconds(1)))
                .thenReturn(MinuteKlineBatch.of(List.of(closed(minute(9)))));
        feed = feed(replay(true, 3, 1440));

        startAndAwaitIdle();

        assertThat(submitted).extracting(PriceRange::from).containsExactly(minute(9));
    }

    @Test
    void F1_aStartupReplayThatFails_keepsThePairReplaying_andIsRetriedFromWhereItStopped() {
        activeEntry(PAIR, M0);
        when(matching.watermark(MarketType.SPOT, PAIR)).thenReturn(Optional.of(M0));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, minute(1)))
                .thenReturn(MinuteKlineBatch.of(List.of(closed(minute(1)))));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, minute(2))).thenAnswer(call -> {
            feed.onMinuteKline(update(minute(3), "96", "97", false, 5));
            throw new IllegalStateException("market down");
        });
        feed = feed(replay(true, 3, 1440));

        startAndAwaitIdle();

        assertThat(submitted)
                .as("minute 1 was replayed before the failure; the held minute 3 waits for minute 2")
                .extracting(PriceRange::from)
                .containsExactly(minute(1));
        assertThat(errors()).hasSize(1);
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("not matched"));
    }

    /** F1: a pair lookup that throws once; the retry replays the hole before anything after it reaches the engine. */
    @Test
    void F1_aLiveHoleWhoseReplayThrowsOnce_isReplayedOnTheRetry_beforeTheMinutesAfterIt() {
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, minute(2)))
                .thenThrow(new IllegalStateException("pair lookup failed"))
                .thenReturn(MinuteKlineBatch.of(List.of(closed(minute(2)), closed(minute(3)))));
        feed = feed(replay(true, 0, 1440), RETRY);
        startAndAwaitIdle();
        feed.onMinuteKline(update(minute(1), "1", "2", true, 60));

        feed.onMinuteKline(update(minute(4), "1", "2", false, 5));
        awaitSubmitted(4);
        feed.onMinuteKline(update(minute(4), "1", "2", true, 60));

        assertThat(submitted)
                .extracting(PriceRange::from, PriceRange::closed)
                .containsExactly(
                        tuple(minute(1), true),
                        tuple(minute(2), true),
                        tuple(minute(3), true),
                        tuple(minute(4), false),
                        tuple(minute(4), true));
        assertThat(errors()).hasSize(1);
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("replay resumed after 1 failures"));
    }

    /**
     * F1: an exchange that keeps failing never lets the pair go live: the closed minute after the hole stays held, so
     * the watermark stays at the last minute before the hole, and only the first failure is an error.
     */
    @Test
    void F1_anExchangeThatKeepsFailing_holdsThePair_withOneError_andNoHoleAccepted() {
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, minute(2)))
                .thenThrow(new IllegalStateException("exchange rejected the request"));
        feed = feed(replay(true, 0, 1440), FAST_RETRY);
        startAndAwaitIdle();
        feed.onMinuteKline(update(minute(1), "1", "2", true, 60));

        feed.onMinuteKline(update(minute(4), "1", "2", false, 5));
        feed.onMinuteKline(update(minute(4), "1", "2", true, 60));
        feed.onMinuteKline(update(minute(5), "1", "2", false, 5));
        verify(market, timeout(5_000).atLeast(5)).closedMinuteKlines(MarketType.SPOT, PAIR, minute(2));

        assertThat(submitted).extracting(PriceRange::from, PriceRange::closed).containsExactly(tuple(minute(1), true));
        assertThat(errors()).hasSize(1);
        assertThat(logs.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("again"));
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("not matched"));
    }

    @Test
    void A04_withTheReplayOff_everyPairGoesLiveAtTheStart_andTheExchangeIsNeverAsked() {
        activeEntry(PAIR, M0);
        feed = feed(replay(false, 3, 1440));
        feed.onMinuteKline(update(minute(9), "96", "97", false, 5));

        startAndAwaitIdle();

        assertThat(submitted).extracting(PriceRange::from).containsExactly(minute(9));
        verifyNoInteractions(market);
        verify(matching, never()).watermark(any(), any());
    }

    @Test
    void NSF07_withMatchingOff_updatesAreIgnored_andNothingStarts() {
        feed = new MinuteKlineFeed(
                worker,
                matching,
                market,
                new MatchingProperties(false, 1, 1, RETRY, 60, Duration.ofSeconds(1), replay(true, 3, 1440)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                recording);

        feed.onMinuteKline(update(M0, "99", "101", false, 1));
        feed.start();

        verify(worker, never()).start();
        assertThat(submitted).isEmpty();
        assertThat(threads).isEmpty();
    }

    @Test
    void NSF07_whenTheEngineDoesNotStart_nothingIsReplayedOrHandedOn() {
        when(worker.isRunning()).thenReturn(false);
        feed = feed(replay(true, 3, 1440));
        feed.onMinuteKline(update(M0, "99", "101", false, 1));

        feed.start();

        assertThat(threads).isEmpty();
        assertThat(submitted).isEmpty();
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    void NSF07_heldMinutes_areBounded_theOldestDroppedWithAWarning() {
        feed = feed(replay(false, 3, 2));

        feed.onMinuteKline(update(minute(1), "1", "2", true, 60));
        feed.onMinuteKline(update(minute(2), "1", "2", true, 60));
        feed.onMinuteKline(update(minute(3), "1", "2", false, 1));
        startAndAwaitIdle();

        assertThat(submitted).extracting(PriceRange::from).containsExactly(minute(2), minute(3));
        assertThat(logs.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("dropped"));
    }

    @Test
    void NSF07_anUpdateStampedBeforeItsCandleOpened_isTakenAtTheOpenTime() {
        feed = feed(replay(true, 3, 1440));
        startAndAwaitIdle();

        feed.onMinuteKline(
                new MinuteKline(MarketType.SPOT, PAIR, M0, BigDecimal.ONE, BigDecimal.TEN, false, M0.minusMillis(5)));

        assertThat(submitted.getFirst().at()).isEqualTo(M0);
    }

    @Test
    void NSF07_aStopThenAStart_replaysAgain_andTheOtherPairsWaitInBetween() {
        activeEntry(PAIR, M0);
        when(matching.watermark(MarketType.SPOT, PAIR)).thenReturn(Optional.of(minute(9)));
        feed = feed(replay(true, 0, 1440));
        startAndAwaitIdle();

        feed.stop();
        feed.onMinuteKline(new MinuteKline(
                MarketType.SPOT, OTHER_PAIR, minute(7), BigDecimal.ONE, BigDecimal.TEN, false, minute(7)));
        assertThat(submitted).isEmpty();
        startAndAwaitIdle();

        assertThat(submitted).extracting(PriceRange::pairId).containsExactly(OTHER_PAIR);
        verify(market, times(2)).closedMinuteKlines(MarketType.SPOT, PAIR, minute(10));
        verify(worker, times(2)).start();
    }

    @Test
    void A04_anEntryOfAnotherMarket_isReplayedOnItsOwnMarket() {
        when(matching.activeEntries())
                .thenReturn(List.of(new TrackedEntry(
                        UUID.randomUUID(), MarketType.FUTURES, PAIR, Direction.SHORT, BigDecimal.TEN, M0)));
        when(matching.watermark(MarketType.FUTURES, PAIR)).thenReturn(Optional.of(M0));
        feed = feed(replay(true, 0, 1440));

        startAndAwaitIdle();

        verify(market, atLeastOnce()).closedMinuteKlines(MarketType.FUTURES, PAIR, minute(1));
        verify(market, never()).closedMinuteKlines(eq(MarketType.SPOT), any(), any());
    }

    private static final MatchingProperties.Retry RETRY =
            new MatchingProperties.Retry(Duration.ZERO, Duration.ZERO, 0, Duration.ofMinutes(10));

    private static final MatchingProperties.Retry SLOW_RETRY =
            new MatchingProperties.Retry(Duration.ofHours(1), Duration.ofHours(1), 0, Duration.ofMinutes(10));

    private static final MatchingProperties.Retry FAST_RETRY =
            new MatchingProperties.Retry(Duration.ofMillis(1), Duration.ofMillis(2), 0, Duration.ofMinutes(10));

    /** A failed replay is retried an hour later, so a test that serves nothing sees one try. */
    private MinuteKlineFeed feed(MatchingProperties.Replay replay) {
        return feed(replay, SLOW_RETRY);
    }

    private MinuteKlineFeed feed(MatchingProperties.Replay replay, MatchingProperties.Retry retry) {
        return new MinuteKlineFeed(
                worker,
                matching,
                market,
                new MatchingProperties(true, 1, 100, retry, 60, Duration.ofSeconds(5), replay),
                Clock.fixed(NOW, ZoneOffset.UTC),
                recording);
    }

    private void startAndAwaitIdle() {
        feed.start();
        awaitIdle();
    }

    private void awaitIdle() {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!feed.isIdle()) {
            assertThat(System.nanoTime()).as("the replays end in time").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    /** Waits for retried replays, which {@link MinuteKlineFeed#isIdle()} does not count. */
    private void awaitSubmitted(int count) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (submitted.size() < count) {
            assertThat(System.nanoTime()).as("the retried replay ends in time").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    private List<ILoggingEvent> errors() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
    }

    private void activeEntry(UUID pair, Instant activatedAt) {
        entries.add(new TrackedEntry(
                UUID.randomUUID(), MarketType.SPOT, pair, Direction.LONG, new BigDecimal("100"), activatedAt));
        when(matching.activeEntries()).thenReturn(List.copyOf(entries));
    }

    /** The exchange serves the closed candles of minutes {@code first} to {@code last}. */
    private void serveClosed(int first, int last) {
        for (int n = first; n <= last; n++) {
            served.add(closed(minute(n)));
        }
    }

    private static MatchingProperties.Replay replay(boolean enabled, int catchUps, int maxHeld) {
        return new MatchingProperties.Replay(enabled, Duration.ofHours(24), Duration.ZERO, catchUps, maxHeld);
    }

    private static Instant minute(long n) {
        return M0.plus(Duration.ofMinutes(n));
    }

    private static MinuteKline update(Instant open, String low, String high, boolean closed, long seconds) {
        return new MinuteKline(
                MarketType.SPOT,
                PAIR,
                open,
                new BigDecimal(low),
                new BigDecimal(high),
                closed,
                open.plusSeconds(seconds).minusMillis(closed ? 1 : 0));
    }

    private static MinuteKline closed(Instant open) {
        return update(open, "100", "101", true, 60);
    }

    private static PriceRange range(Instant open, String low, String high, boolean closed, long seconds) {
        return new PriceRange(
                MarketType.SPOT,
                PAIR,
                new BigDecimal(low),
                new BigDecimal(high),
                open,
                open.plusSeconds(seconds).minusMillis(closed ? 1 : 0),
                closed);
    }
}
