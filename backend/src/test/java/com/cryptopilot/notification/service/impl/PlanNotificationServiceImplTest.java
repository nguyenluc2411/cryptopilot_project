package com.cryptopilot.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.trading.event.PositionClosed;
import com.cryptopilot.trading.event.TradingPlanExpired;
import com.cryptopilot.trading.event.TradingPlanInvalidated;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.InvalidationReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

/**
 * The exact texts of MSG23 and MSG24, with {@code {pnl}} the net P/L of the journal row (Q-56): the T-044 vectors (a)
 * to (c); and of MSG53 and MSG54 with each invalidation reason.
 *
 * <p>Rule: BR-35, BR-38, BR-68, NSF-09; Q-56.
 */
class PlanNotificationServiceImplTest {

    private final NotificationWriter writer = mock(NotificationWriter.class);
    private final PlanNotificationServiceImpl notifications = new PlanNotificationServiceImpl(writer);

    @Test
    void MSG23_aTakeProfit_namesThePairTheReasonThePriceAndTheNet() {
        PositionClosed closed = closed("BTCUSDT", CloseReason.TAKE_PROFIT, "115.000000000000", "29.91400000");

        notifications.positionClosed(closed);

        NewNotification written = written();
        assertThat(written.content()).isEqualTo("BTCUSDT position closed by take profit at 115. P/L: 29.914.");
        assertThat(written.title()).isEqualTo("Position closed");
        assertThat(written.type()).isEqualTo(NotificationType.PLAN);
        assertThat(written.referenceType()).isEqualTo(NotificationReference.PLAN);
        assertThat(written.referenceId()).isEqualTo(closed.planId());
        assertThat(written.recipientId()).isEqualTo(closed.userId());
        assertThat(written.sourceKey()).isEqualTo("POSITION_CLOSED:" + closed.positionId());
    }

    @Test
    void MSG23_aStopLoss_isNamedAsSuch() {
        notifications.positionClosed(closed("ETHUSDT", CloseReason.STOP_LOSS, "105", "-10.20500000"));

        assertThat(written().content()).isEqualTo("ETHUSDT position closed by stop loss at 105. P/L: -10.205.");
    }

    @Test
    void MSG24_aLiquidation_namesTheLossOfTheJournalRow() {
        notifications.positionClosed(closed("BTCUSDT", CloseReason.LIQUIDATION, "90.37", "-20.02296000"));

        NewNotification written = written();
        assertThat(written.content()).isEqualTo("BTCUSDT position was liquidated at 90.37. Loss: -20.02296.");
        assertThat(written.title()).isEqualTo("Position liquidated");
    }

    @Test
    void MSG23_aPairWhoseSymbolIsUnknown_isTheTradersPosition() {
        notifications.positionClosed(closed(null, CloseReason.TAKE_PROFIT, "115", "0"));

        assertThat(written().content()).isEqualTo("Your position closed by take profit at 115. P/L: 0.");
    }

    @Test
    void MSG53_anExpiredPlan_namesThePairAndTheDirection() {
        TradingPlanExpired expired = new TradingPlanExpired(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.SPOT,
                UUID.randomUUID(),
                Direction.LONG,
                "ETHUSDT",
                Instant.parse("2026-10-25T09:00:00Z"));

        notifications.expired(expired);

        NewNotification written = written();
        assertThat(written.content()).isEqualTo("ETHUSDT LONG plan expired before its entry was filled.");
        assertThat(written.title()).isEqualTo("Plan expired");
        assertThat(written.referenceId()).isEqualTo(expired.planId());
        assertThat(written.recipientId()).isEqualTo(expired.userId());
        assertThat(written.sourceKey()).isEqualTo("PLAN_EXPIRED:" + expired.planId());
    }

    @ParameterizedTest
    @CsvSource({
        "TAKE_PROFIT_REACHED, the take profit was reached first",
        "STOP_LOSS_REACHED, the stop loss was reached first",
        "INVALIDATION_PRICE, the invalidation price was reached"
    })
    void MSG54_anInvalidatedPlan_namesTheReason(InvalidationReason reason, String text) {
        TradingPlanInvalidated invalidated = new TradingPlanInvalidated(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.FUTURES,
                Direction.SHORT,
                "BTCUSDT",
                reason,
                Instant.parse("2026-10-25T09:00:00Z"));

        notifications.invalidated(invalidated);

        NewNotification written = written();
        assertThat(written.content())
                .isEqualTo("BTCUSDT SHORT plan was invalidated before its entry was filled: " + text + ".");
        assertThat(written.title()).isEqualTo("Plan invalidated");
        assertThat(written.sourceKey()).isEqualTo("PLAN_INVALIDATED:" + invalidated.planId());
    }

    @Test
    void MSG54_aPairWhoseSymbolIsUnknown_isTheTradersPlan() {
        notifications.invalidated(new TradingPlanInvalidated(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.SPOT,
                Direction.LONG,
                null,
                InvalidationReason.INVALIDATION_PRICE,
                Instant.parse("2026-10-25T09:00:00Z")));

        assertThat(written().content())
                .isEqualTo("Your LONG plan was invalidated before its entry was filled: the invalidation price was"
                        + " reached.");
    }

    private NewNotification written() {
        ArgumentCaptor<NewNotification> written = ArgumentCaptor.forClass(NewNotification.class);
        verify(writer).write(written.capture());
        return written.getValue();
    }

    private static PositionClosed closed(String symbol, CloseReason reason, String exitPrice, String net) {
        return new PositionClosed(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.FUTURES,
                symbol,
                reason,
                new BigDecimal(exitPrice),
                BigDecimal.ONE,
                BigDecimal.ONE,
                new BigDecimal(net),
                false,
                Instant.parse("2026-10-25T09:00:00Z"));
    }
}
