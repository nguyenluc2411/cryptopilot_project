package com.cryptopilot.watchlist.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.watchlist.model.PriceAlert;
import com.cryptopilot.watchlist.model.PriceAlertHit;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The evaluation of PRICE alerts on one pair: crossing and level semantics, trigger modes, throttling and stale or
 * late prices. Pure Java on a test clock.
 *
 * <p>Rule: NSF-06, BR-18, BR-19, BR-20; TECHNICAL_DESIGN 7.9; D-87, D-88.
 */
class PriceAlertBookTest {

    private static final Instant T0 = Instant.parse("2026-10-05T08:10:00Z");
    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000c001");

    private final MutableTestClock clock = new MutableTestClock(T0);
    private final PriceAlertBook book = new PriceAlertBook(clock, Duration.ofSeconds(1));

    @Test
    void BR20_crossAbove_firesWhenThePreviousIsBelowAndTheCurrentReachesTheThreshold() {
        PriceAlert alert = alert(ConditionOperator.CROSS_ABOVE, "100", TriggerMode.EVERY_TIME);
        book.put(alert);

        assertThat(evaluate("99", 0)).isEmpty();
        assertThat(evaluate("100", 1)).extracting(hit -> hit.alert().alertId()).containsExactly(alert.alertId());
    }

    @Test
    void BR20_crossAbove_fromExactlyTheThreshold_doesNotFire() {
        book.put(alert(ConditionOperator.CROSS_ABOVE, "100", TriggerMode.EVERY_TIME));

        evaluate("100", 0);

        assertThat(evaluate("101", 1)).isEmpty();
    }

    @Test
    void BR20_crossAbove_fromAboveTheThreshold_doesNotFire() {
        book.put(alert(ConditionOperator.CROSS_ABOVE, "100", TriggerMode.EVERY_TIME));

        evaluate("101", 0);

        assertThat(evaluate("102", 1)).isEmpty();
    }

    @Test
    void BR20_crossBelow_isTheMirror_firingAtTheThreshold_notFromIt_notFromBelow() {
        PriceAlert alert = alert(ConditionOperator.CROSS_BELOW, "100", TriggerMode.EVERY_TIME);
        book.put(alert);

        evaluate("101", 0);
        assertThat(evaluate("100", 1)).extracting(hit -> hit.alert().alertId()).containsExactly(alert.alertId());
        assertThat(evaluate("99", 2)).as("from exactly the threshold").isEmpty();
        assertThat(evaluate("98", 3)).as("from below").isEmpty();
    }

    @Test
    void BR20_aJumpOverSeveralThresholds_firesEachOfThem_inPriceOrder() {
        PriceAlert first = alert(ConditionOperator.CROSS_ABOVE, "101", TriggerMode.EVERY_TIME);
        PriceAlert second = alert(ConditionOperator.CROSS_ABOVE, "102", TriggerMode.EVERY_TIME);
        PriceAlert third = alert(ConditionOperator.CROSS_ABOVE, "103", TriggerMode.EVERY_TIME);
        PriceAlert beyond = alert(ConditionOperator.CROSS_ABOVE, "104", TriggerMode.EVERY_TIME);
        List.of(third, beyond, first, second).forEach(book::put);

        evaluate("100", 0);

        assertThat(evaluate("103", 1))
                .extracting(hit -> hit.alert().alertId())
                .containsExactly(first.alertId(), second.alertId(), third.alertId());
    }

    @Test
    void D88_withoutAPreviousPrice_aCrossDoesNotFire_butALevelDoes() {
        PriceAlert cross = alert(ConditionOperator.CROSS_ABOVE, "100", TriggerMode.EVERY_TIME);
        PriceAlert above = alert(ConditionOperator.GREATER_THAN, "100", TriggerMode.EVERY_TIME);
        PriceAlert below = alert(ConditionOperator.LESS_THAN, "200", TriggerMode.EVERY_TIME);
        List.of(cross, above, below).forEach(book::put);

        assertThat(evaluate("150", 0))
                .extracting(hit -> hit.alert().alertId())
                .containsExactlyInAnyOrder(above.alertId(), below.alertId());
    }

    @Test
    void D88_aLevelIsStrict_soThePriceAtTheThresholdMeetsNeither() {
        book.put(alert(ConditionOperator.GREATER_THAN, "100", TriggerMode.EVERY_TIME));
        book.put(alert(ConditionOperator.LESS_THAN, "100", TriggerMode.EVERY_TIME));

        assertThat(evaluate("100", 0)).isEmpty();
    }

    @Test
    void BR19_once_firesOnce_andLeavesTheBook() {
        PriceAlert alert = alert(ConditionOperator.GREATER_THAN, "100", TriggerMode.ONCE);
        book.put(alert);

        List<PriceAlertHit> hits = evaluate("101", 0);
        assertThat(hits).hasSize(1);
        book.triggered(hits.getFirst());

        assertThat(book.contains(alert.alertId())).isFalse();
        assertThat(evaluate("102", 1)).isEmpty();
    }

    @Test
    void BR19_oncePerBar_firesOncePerHourCandle_andAgainInTheNextOne() {
        PriceAlert alert = alert(ConditionOperator.GREATER_THAN, "100", TriggerMode.ONCE_PER_BAR);
        book.put(alert);

        List<PriceAlertHit> first = evaluate("101", 0);
        assertThat(first).singleElement().satisfies(hit -> assertThat(hit.barOpenTime())
                .isEqualTo(Instant.parse("2026-10-05T08:00:00Z")));
        book.triggered(first.getFirst());
        assertThat(evaluate("102", 60)).as("same 1h candle").isEmpty();

        clock.set(Instant.parse("2026-10-05T09:00:05Z"));
        assertThat(book.offer(new BigDecimal("103"), Instant.parse("2026-10-05T09:00:05Z")))
                .isTrue();
        assertThat(book.evaluate()).as("next 1h candle").hasSize(1);
    }

    @Test
    void BR19_everyTime_waitsTheWholeCooldown_aSecondShortIsNotEnough() {
        PriceAlert alert = alert(ConditionOperator.GREATER_THAN, "100", TriggerMode.EVERY_TIME);
        book.put(alert);

        List<PriceAlertHit> first = evaluate("101", 0);
        book.triggered(first.getFirst());

        assertThat(evaluate("101.5", 5 * 60 - 1))
                .as("cooldown minus one second")
                .isEmpty();
        assertThat(evaluate("102", 5 * 60)).as("exactly the cooldown").hasSize(1);
    }

    /** D-88: a level keeps firing while it holds, as often as the cooldown allows. */
    @Test
    void D88_greaterThanEveryTime_firesAgainAfterEachCooldown_whileTheConditionHolds() {
        PriceAlert alert = alert(ConditionOperator.GREATER_THAN, "100", TriggerMode.EVERY_TIME);
        book.put(alert);

        int fired = 0;
        for (int second = 0; second <= 15 * 60; second += 30) {
            List<PriceAlertHit> hits = evaluate("150", second);
            hits.forEach(book::triggered);
            fired += hits.size();
        }

        assertThat(fired).as("at 0, 5 and 10 and 15 minutes").isEqualTo(4);
    }

    @Test
    void BR19_anExpiredAlert_isNotEvaluated_evenBeforeTheSweep() {
        book.put(withExpiry(alert(ConditionOperator.GREATER_THAN, "100", TriggerMode.EVERY_TIME), T0.plusSeconds(1)));

        assertThat(evaluate("101", 0)).hasSize(1);
        assertThat(evaluate("102", 1)).as("at the expiry").isEmpty();
    }

    @Test
    void NSF06_aPriceNotNewerThanTheLastOne_isDropped() {
        book.put(alert(ConditionOperator.CROSS_ABOVE, "100", TriggerMode.EVERY_TIME));
        evaluate("99", 10);
        clock.advance(Duration.ofSeconds(1));

        assertThat(book.offer(new BigDecimal("101"), T0.plusSeconds(9)))
                .as("older")
                .isFalse();
        assertThat(book.offer(new BigDecimal("101"), T0.plusSeconds(10)))
                .as("same instant")
                .isFalse();
        assertThat(book.evaluate()).isEmpty();
        assertThat(book.hasPending()).isFalse();
    }

    @Test
    void NSF06_afterAReconnection_aStalePreviousPriceIsForgotten_soACrossDoesNotFire() {
        book.put(alert(ConditionOperator.CROSS_ABOVE, "100", TriggerMode.EVERY_TIME));
        evaluate("99", 0);

        clock.advance(Duration.ofMinutes(10));
        book.forgetPreviousBefore(clock.instant().minus(Duration.ofMinutes(5)));

        assertThat(evaluate("101", 10 * 60 + 1)).isEmpty();
        assertThat(evaluate("99", 10 * 60 + 2)).isEmpty();
        assertThat(evaluate("100", 10 * 60 + 3))
                .as("a cross measured from a fresh price")
                .hasSize(1);
    }

    @Test
    void NSF06_afterAReconnection_aFreshPreviousPriceIsKept_soACrossStillFires() {
        book.put(alert(ConditionOperator.CROSS_ABOVE, "100", TriggerMode.EVERY_TIME));
        evaluate("99", 0);

        clock.advance(Duration.ofMinutes(2));
        book.forgetPreviousBefore(clock.instant().minus(Duration.ofMinutes(5)));

        assertThat(evaluate("101", 2 * 60)).hasSize(1);
    }

    @Test
    void SRS344_manyPricesWithinASecond_areEvaluatedOnce_onTheLatest() {
        PriceAlert alert = alert(ConditionOperator.CROSS_ABOVE, "100", TriggerMode.EVERY_TIME);
        book.put(alert);
        evaluate("98", 0);

        clock.set(T0.plusMillis(200));
        book.offer(new BigDecimal("99"), T0.plusMillis(200));
        assertThat(book.evaluate()).as("within the second").isEmpty();
        clock.set(T0.plusMillis(600));
        book.offer(new BigDecimal("101"), T0.plusMillis(600));
        assertThat(book.evaluate()).as("still within the second").isEmpty();
        assertThat(book.hasPending()).isTrue();

        clock.set(T0.plusMillis(1000));
        assertThat(book.evaluate()).singleElement().satisfies(hit -> assertThat(hit.observedValue())
                .isEqualByComparingTo("101"));
        assertThat(book.hasPending()).isFalse();
    }

    @Test
    void NSF06_putAndRemove_keepTheIndexConsistent_withTwoAlertsOnOneThreshold() {
        PriceAlert first = alert(ConditionOperator.GREATER_THAN, "100", TriggerMode.EVERY_TIME);
        PriceAlert second = alert(ConditionOperator.GREATER_THAN, "100.0", TriggerMode.EVERY_TIME);
        book.put(first);
        book.put(second);

        assertThat(book.remove(first.alertId())).isTrue();
        assertThat(book.remove(first.alertId())).isFalse();

        assertThat(evaluate("101", 0)).extracting(hit -> hit.alert().alertId()).containsExactly(second.alertId());
        assertThat(book.size()).isEqualTo(1);
    }

    /** Offers a price stamped {@code seconds} after T0, moves the clock there and evaluates. */
    private List<PriceAlertHit> evaluate(String price, long seconds) {
        Instant at = T0.plusSeconds(seconds);
        clock.set(at);
        book.offer(new BigDecimal(price), at);
        return book.evaluate();
    }

    private static PriceAlert alert(ConditionOperator condition, String threshold, TriggerMode mode) {
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
                0,
                null,
                null,
                0);
    }

    private static PriceAlert withExpiry(PriceAlert alert, Instant expiresAt) {
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
                expiresAt,
                true,
                false,
                false,
                0,
                null,
                null,
                0);
    }
}
