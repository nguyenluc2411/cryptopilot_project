package com.cryptopilot.journal.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.journal.dto.request.ManualTradeRequest;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The rules of SRS 3.6.2 a manual trade is checked against, each under its own field, and its realized P/L by BR-35.
 *
 * <p>Rule: BR-21, BR-22, BR-35, BR-41; SRS 3.6.2; MSG01, MSG15, MSG16.
 */
class ManualTradeRulesTest {

    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-0000000000f1");
    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    private static final Instant ENTRY = NOW.minusSeconds(7200);
    private static final Optional<PairListing> LISTED = Optional.of(new PairListing(PAIR, "BTCUSDT", true, true));

    @Test
    void BR41_aValidClosedSpotLong_breaksNoRule() {
        assertThat(violations(spot(Direction.LONG, "95", "110", "105", ENTRY.plusSeconds(60))))
                .isEmpty();
    }

    @Test
    void BR41_aValidOpenFuturesShort_breaksNoRule() {
        assertThat(violations(futures(Direction.SHORT, 10, "105", "90", null, null)))
                .isEmpty();
    }

    @Test
    void BR21_aSpotShort_isOnTheWrongSide() {
        assertThat(violations(spot(Direction.SHORT, null, null, null, null))).containsEntry("direction", "MSG16");
    }

    @Test
    void BR21_spotTakesNoLeverageNorFunding_andFuturesNeedsALeverageFrom1To125() {
        ManualTradeRequest spot = spot(Direction.LONG, null, null, null, null);
        assertThat(violations(with(spot, 5, new BigDecimal("1"))))
                .containsEntry("leverage", "MSG15")
                .containsEntry("fundingFeeAmount", "MSG15");
        assertThat(violations(futures(Direction.LONG, null, null, null, null, null)))
                .containsEntry("leverage", "MSG01");
        assertThat(violations(futures(Direction.LONG, 0, null, null, null, null)))
                .containsEntry("leverage", "MSG15");
        assertThat(violations(futures(Direction.LONG, 126, null, null, null, null)))
                .containsEntry("leverage", "MSG15");
        assertThat(violations(futures(Direction.LONG, 125, null, null, null, new BigDecimal("-0.5"))))
                .isEmpty();
    }

    @Test
    void BR41_anUnknownPair_orOneNotListedOnTheMarket_isRefused() {
        ManualTradeRequest spot = spot(Direction.LONG, null, null, null, null);
        assertThat(ManualTradeRules.violations(spot, Optional.empty(), NOW)).containsEntry("pairId", "MSG15");
        assertThat(ManualTradeRules.violations(spot, Optional.of(new PairListing(PAIR, "BTCUSDT", false, true)), NOW))
                .containsEntry("pairId", "MSG15");
        assertThat(ManualTradeRules.violations(
                        futures(Direction.LONG, 5, null, null, null, null),
                        Optional.of(new PairListing(PAIR, "BTCUSDT", true, false)),
                        NOW))
                .containsEntry("pairId", "MSG15");
    }

    @Test
    void BR41_anEntryInTheFuture_isRefused() {
        ManualTradeRequest later = new ManualTradeRequest(
                PAIR,
                MarketType.SPOT,
                Direction.LONG,
                null,
                BigDecimal.TEN,
                BigDecimal.ONE,
                NOW.plusSeconds(1),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertThat(violations(later)).containsEntry("entryTime", "MSG15");
    }

    /** An exit has its price and its time, not before the entry nor in the future; the entry time itself is fine. */
    @Test
    void BR41_anExit_needsBothItsPriceAndTime_inTheRightOrder() {
        assertThat(violations(spot(Direction.LONG, null, null, "105", null))).containsEntry("exitTime", "MSG01");
        assertThat(violations(spot(Direction.LONG, null, null, null, ENTRY))).containsEntry("exitPrice", "MSG01");
        assertThat(violations(spot(Direction.LONG, null, null, "105", ENTRY.minusSeconds(1))))
                .containsEntry("exitTime", "MSG15");
        assertThat(violations(spot(Direction.LONG, null, null, "105", NOW.plusSeconds(1))))
                .containsEntry("exitTime", "MSG15");
        assertThat(violations(spot(Direction.LONG, null, null, "105", ENTRY))).isEmpty();
    }

    /** BR-22: LONG stop loss below and take profit above the entry, SHORT the reverse; an equal price is refused. */
    @Test
    void BR22_theStopLossAndTakeProfit_mustLieOnTheirSides() {
        assertThat(violations(spot(Direction.LONG, "100", "100", null, null)))
                .containsEntry("stopLoss", "MSG16")
                .containsEntry("takeProfit", "MSG16");
        assertThat(violations(futures(Direction.SHORT, 5, "95", "110", null, null)))
                .containsEntry("stopLoss", "MSG16")
                .containsEntry("takeProfit", "MSG16");
        assertThat(violations(spot(Direction.LONG, "95", null, null, null))).isEmpty();
    }

    /** BR-35: LONG (110 − 100) × 2 = 20; SHORT (100 − 110) × 2 = −20; open: none. */
    @Test
    void BR35_theRealizedPnl_isThePriceResult_atTheAmountScale() {
        assertThat(ManualTradeRules.realizedPnl(twoOf(Direction.LONG))).isEqualTo(new BigDecimal("20.00000000"));
        assertThat(ManualTradeRules.realizedPnl(twoOf(Direction.SHORT))).isEqualTo(new BigDecimal("-20.00000000"));
        assertThat(ManualTradeRules.realizedPnl(spot(Direction.LONG, null, null, null, null)))
                .isNull();
    }

    private static Map<String, String> violations(ManualTradeRequest trade) {
        return ManualTradeRules.violations(trade, LISTED, NOW);
    }

    private static ManualTradeRequest twoOf(Direction direction) {
        return new ManualTradeRequest(
                PAIR,
                MarketType.FUTURES,
                direction,
                5,
                new BigDecimal("100"),
                new BigDecimal("2"),
                ENTRY,
                null,
                null,
                new BigDecimal("110"),
                ENTRY.plusSeconds(60),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static ManualTradeRequest spot(Direction direction, String stop, String take, String exit, Instant exitAt) {
        return new ManualTradeRequest(
                PAIR,
                MarketType.SPOT,
                direction,
                null,
                new BigDecimal("100"),
                BigDecimal.ONE,
                ENTRY,
                stop == null ? null : new BigDecimal(stop),
                take == null ? null : new BigDecimal(take),
                exit == null ? null : new BigDecimal(exit),
                exitAt,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static ManualTradeRequest futures(
            Direction direction, Integer leverage, String stop, String take, String exit, BigDecimal funding) {
        return new ManualTradeRequest(
                PAIR,
                MarketType.FUTURES,
                direction,
                leverage,
                new BigDecimal("100"),
                BigDecimal.ONE,
                ENTRY,
                stop == null ? null : new BigDecimal(stop),
                take == null ? null : new BigDecimal(take),
                exit == null ? null : new BigDecimal(exit),
                exit == null ? null : ENTRY.plusSeconds(60),
                null,
                funding,
                null,
                null,
                null,
                null);
    }

    private static ManualTradeRequest with(ManualTradeRequest trade, Integer leverage, BigDecimal funding) {
        return new ManualTradeRequest(
                trade.pairId(),
                trade.market(),
                trade.direction(),
                leverage,
                trade.entryPrice(),
                trade.quantity(),
                trade.entryTime(),
                trade.stopLoss(),
                trade.takeProfit(),
                trade.exitPrice(),
                trade.exitTime(),
                trade.feeAmount(),
                funding,
                trade.strategyId(),
                trade.setupDescription(),
                trade.lessonsLearned(),
                trade.mistakes());
    }
}
