package com.cryptopilot.watchlist.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.watchlist.calculator.PriceAlertBook.ReplayedPrice;
import com.cryptopilot.watchlist.model.PriceAlert;
import com.cryptopilot.watchlist.model.PriceAlertHit;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The replay of missed prices through a book (T-111, D-87).
 *
 * <p>Invariants: (1) a replayed path fires what a live run over the same closes would have fired, at most once per
 * alert; (2) the first price only seeds the path; (3) no price at or before an alert's last trigger fires it; (4) the
 * trigger mode is judged at the price's own time; (5) an alert expired now never fires; (6) hits are recorded at the
 * clock's now; (7) the replay never moves the book's previous price back behind a newer one; (8) no price at or before
 * the time an alert was armed (created, edited, resumed) fires it.
 *
 * <p>Rule: NSF-06, BR-19, BR-20; D-87.
 */
class PriceAlertBookReplayTest {

    private static final Instant GAP = Instant.parse("2026-11-22T08:00:00Z");
    private static final Instant NOW = GAP.plus(Duration.ofMinutes(10));
    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000c111");

    private final MutableTestClock clock = new MutableTestClock(NOW);
    private final PriceAlertBook book = new PriceAlertBook(clock, Duration.ofSeconds(1), Duration.ofMinutes(5));

    /** Every condition against paths that meet it, miss it, or only touch it in the seed. */
    @ParameterizedTest(name = "{0} {1} over {2} fires {3}")
    @CsvSource(
            delimiter = ';',
            value = {
                "CROSS_ABOVE; 100; 99 99.5 100.5 101; true",
                "CROSS_ABOVE; 100; 99 99.5 99.9; false",
                "CROSS_ABOVE; 100; 101 102 103; false",
                "CROSS_ABOVE; 100; 99 100; true",
                "CROSS_BELOW; 100; 101 100.5 99; true",
                "CROSS_BELOW; 100; 99 98 97; false",
                "GREATER_THAN; 100; 99 99 100.1; true",
                "GREATER_THAN; 100; 101 99 99; false",
                "LESS_THAN; 100; 101 101 99.9; true",
                "LESS_THAN; 100; 99 101 101; false",
                "CROSS_ABOVE; 100; 99 101 99 101; true"
            })
    void T111_aReplayedPath_firesWhatTheLivePathWould_onceAtMost(
            ConditionOperator condition, String threshold, String closes, boolean fires) {
        PriceAlert alert = alert(condition, threshold, TriggerMode.EVERY_TIME, null);
        book.put(alert);

        List<PriceAlertHit> hits = book.replay(path(closes));

        assertThat(hits).hasSize(fires ? 1 : 0);
        hits.forEach(hit -> {
            assertThat(hit.alert().alertId()).isEqualTo(alert.alertId());
            assertThat(hit.at()).isEqualTo(NOW);
        });
    }

    @Test
    void T111_aPriceAtOrBeforeTheLastTrigger_doesNotFireAgain() {
        Instant lastTrigger = GAP.plus(Duration.ofMinutes(3));
        book.put(alert(ConditionOperator.GREATER_THAN, "100", TriggerMode.EVERY_TIME, lastTrigger));

        assertThat(book.replay(path("101 101 101")))
                .as("closes at minutes 1 and 2, before the trigger")
                .isEmpty();
        PriceAlertBook later = new PriceAlertBook(clock, Duration.ofSeconds(1), Duration.ofMinutes(5));
        PriceAlert cooled = alert(ConditionOperator.GREATER_THAN, "100", TriggerMode.EVERY_TIME, GAP);
        later.put(cooled);
        assertThat(later.replay(path("99 101 101 101 101 101 101")))
                .as("the 5-minute cooldown has passed at the close of minute 5")
                .singleElement()
                .satisfies(hit -> assertThat(hit.observedValue()).isEqualByComparingTo("101"));
    }

    @Test
    void T111_onceAnHour_isJudgedOnTheReplayedCandlesHour() {
        PriceAlert alert = new PriceAlert(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PAIR,
                "BTCUSDT",
                MarketType.SPOT,
                ConditionOperator.GREATER_THAN,
                new BigDecimal("100"),
                TriggerMode.ONCE_PER_BAR,
                null,
                null,
                true,
                false,
                false,
                1,
                GAP.minus(Duration.ofMinutes(30)),
                GAP.minus(Duration.ofHours(1)).truncatedTo(java.time.temporal.ChronoUnit.HOURS),
                3,
                Instant.EPOCH);
        book.put(alert);

        assertThat(book.replay(path("99 101"))).singleElement().satisfies(hit -> assertThat(hit.barOpenTime())
                .isEqualTo(GAP));
    }

    @Test
    void T111_anAlertExpiredNow_isNotFiredLate() {
        PriceAlert alert = new PriceAlert(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PAIR,
                "BTCUSDT",
                MarketType.SPOT,
                ConditionOperator.GREATER_THAN,
                new BigDecimal("100"),
                TriggerMode.ONCE,
                null,
                GAP.plus(Duration.ofMinutes(5)),
                true,
                false,
                false,
                0,
                null,
                null,
                0,
                Instant.EPOCH);
        book.put(alert);

        assertThat(book.replay(path("99 101 102"))).isEmpty();
    }

    @Test
    void T111_aPathOfOnePrice_onlySeeds_andFiresNothing() {
        book.put(alert(ConditionOperator.GREATER_THAN, "100", TriggerMode.ONCE, null));

        assertThat(book.replay(path("101"))).isEmpty();
        assertThat(book.replay(List.of())).isEmpty();
        assertThat(book.lastPriceAt()).isEmpty();
    }

    @Test
    void T111_theNextLivePrice_isMeasuredFromWhereTheReplayEnded() {
        PriceAlert alert = alert(ConditionOperator.CROSS_ABOVE, "100", TriggerMode.EVERY_TIME, null);
        book.put(alert);
        book.replay(path("97 98 99"));
        assertThat(book.lastPriceAt()).contains(GAP.plus(Duration.ofMinutes(3)));

        book.offer(new BigDecimal("101"), NOW.plusSeconds(1));
        clock.set(NOW.plusSeconds(1));

        assertThat(book.evaluate())
                .as("99, the replay's last close, to 101 is a cross")
                .singleElement()
                .satisfies(hit -> assertThat(hit.alert().alertId()).isEqualTo(alert.alertId()));
    }

    @Test
    void T111_aReplay_neverPutsThePreviousPriceBehindANewerOne() {
        PriceAlert alert = alert(ConditionOperator.CROSS_ABOVE, "100", TriggerMode.EVERY_TIME, null);
        book.put(alert);
        book.offer(new BigDecimal("102"), NOW.minusSeconds(5));
        clock.set(NOW.minusSeconds(5));
        book.evaluate();
        clock.set(NOW);

        book.replay(path("97 98 99"));
        book.offer(new BigDecimal("103"), NOW.plusSeconds(1));
        clock.set(NOW.plusSeconds(1));

        assertThat(book.evaluate())
                .as("measured from the live 102, not the replayed 99")
                .isEmpty();
        assertThat(book.lastPriceAt()).contains(NOW.plusSeconds(1));
    }

    @Test
    void T111_pricesOutOfOrder_areStepsSkipped_notCrosses() {
        PriceAlert alert = alert(ConditionOperator.CROSS_ABOVE, "100", TriggerMode.EVERY_TIME, null);
        book.put(alert);
        List<ReplayedPrice> path = new ArrayList<>(path("99 101"));
        path.add(1, new ReplayedPrice(new BigDecimal("101"), GAP));

        assertThat(book.replay(path))
                .as("the step back in time is skipped; 99 then 101 still crosses")
                .hasSize(1);
    }

    @Test
    void T111_anAlertArmedAfterTheCrossingCandle_doesNotFire() {
        // 99 at minute 1, 101 at minute 2: the cross closes at minute 2, the alert is armed at minute 3.
        book.put(armed(alert(ConditionOperator.CROSS_ABOVE, "100", TriggerMode.EVERY_TIME, null), minute(3)));

        assertThat(book.replay(path("99 101 101"))).isEmpty();
    }

    @Test
    void T111_anAlertArmedAtTheCrossingCandlesClose_doesNotFire() {
        book.put(armed(alert(ConditionOperator.CROSS_ABOVE, "100", TriggerMode.EVERY_TIME, null), minute(2)));

        assertThat(book.replay(path("99 101"))).isEmpty();
    }

    @Test
    void T111_anAlertArmedBeforeTheCrossingCandle_firesOnce() {
        PriceAlert alert = armed(alert(ConditionOperator.CROSS_ABOVE, "100", TriggerMode.EVERY_TIME, null), minute(1));
        book.put(alert);

        assertThat(book.replay(path("99 101 99 101")))
                .singleElement()
                .satisfies(hit -> assertThat(hit.alert().alertId()).isEqualTo(alert.alertId()));
    }

    @Test
    void T111_anEditedThreshold_isJudgedOnlyOnPricesAfterTheEdit() {
        PriceAlert before = armed(alert(ConditionOperator.GREATER_THAN, "200", TriggerMode.EVERY_TIME, null), GAP);
        book.put(before);
        // Edited at minute 3 to "above 100": the 101 of minutes 1 to 3 came before the rule, 99 after it.
        PriceAlert edited = new PriceAlert(
                before.alertId(),
                before.userId(),
                before.watchlistId(),
                PAIR,
                "BTCUSDT",
                MarketType.SPOT,
                ConditionOperator.GREATER_THAN,
                new BigDecimal("100"),
                TriggerMode.EVERY_TIME,
                5,
                null,
                true,
                false,
                false,
                0,
                null,
                null,
                1,
                minute(3));
        book.put(edited);

        assertThat(book.replay(path("101 101 101 99 99"))).isEmpty();
        PriceAlertBook again = new PriceAlertBook(clock, Duration.ofSeconds(1), Duration.ofMinutes(5));
        again.put(edited);
        assertThat(again.replay(path("101 101 101 99 102")))
                .as("102 at minute 5 comes after the edit")
                .singleElement()
                .satisfies(hit -> assertThat(hit.observedValue()).isEqualByComparingTo("102"));
    }

    @Test
    void T111_aTrigger_movesTheArmTimeForward_neverBack() {
        PriceAlert alert = armed(alert(ConditionOperator.GREATER_THAN, "100", TriggerMode.EVERY_TIME, null), minute(3));

        assertThat(alert.triggeredAt(minute(5), null).armedAt()).isEqualTo(minute(5));
        assertThat(alert.triggeredAt(minute(1), null).armedAt()).isEqualTo(minute(3));
    }

    private static Instant minute(long n) {
        return GAP.plus(Duration.ofMinutes(n));
    }

    private static PriceAlert armed(PriceAlert alert, Instant armedAt) {
        return new PriceAlert(
                alert.alertId(),
                alert.userId(),
                alert.watchlistId(),
                alert.pairId(),
                alert.symbol(),
                alert.market(),
                alert.condition(),
                alert.threshold(),
                alert.triggerMode(),
                alert.cooldownMinutes(),
                alert.expiresAt(),
                alert.notifyInApp(),
                alert.notifyEmail(),
                alert.notifyPush(),
                alert.triggerCount(),
                alert.lastTriggeredAt(),
                alert.lastBarOpenTime(),
                alert.version(),
                armedAt);
    }

    /** Closes one minute apart; the first closes one minute after {@link #GAP}. */
    private static List<ReplayedPrice> path(String closes) {
        List<ReplayedPrice> path = new ArrayList<>();
        String[] values = closes.trim().split("\\s+");
        for (int i = 0; i < values.length; i++) {
            path.add(new ReplayedPrice(new BigDecimal(values[i]), GAP.plus(Duration.ofMinutes(i + 1L))));
        }
        return path;
    }

    private static PriceAlert alert(
            ConditionOperator condition, String threshold, TriggerMode mode, Instant lastTriggeredAt) {
        return new PriceAlert(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PAIR,
                "BTCUSDT",
                MarketType.SPOT,
                condition,
                new BigDecimal(threshold),
                mode,
                mode == TriggerMode.EVERY_TIME ? 5 : null,
                null,
                true,
                false,
                false,
                lastTriggeredAt == null ? 0 : 1,
                lastTriggeredAt,
                null,
                0,
                Instant.EPOCH);
    }
}
