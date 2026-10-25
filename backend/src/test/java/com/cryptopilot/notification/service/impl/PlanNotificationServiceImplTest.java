package com.cryptopilot.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.trading.event.PositionClosed;
import com.cryptopilot.trading.model.enums.CloseReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The exact texts of MSG23 and MSG24, with {@code {pnl}} the net P/L of the journal row (Q-56): the T-044 vectors (a)
 * to (c).
 *
 * <p>Rule: BR-35, BR-38; Q-56.
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
