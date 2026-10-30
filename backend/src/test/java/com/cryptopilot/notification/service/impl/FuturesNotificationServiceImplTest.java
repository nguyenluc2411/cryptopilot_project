package com.cryptopilot.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.paper.event.FuturesRiskNotice;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The texts of the held-position warnings of paper Futures (TR-27). */
class FuturesNotificationServiceImplTest {

    private static final Instant AT = Instant.parse("2026-10-30T08:00:00Z");

    private final NotificationWriter writer = mock(NotificationWriter.class);
    private final FuturesNotificationServiceImpl notifications = new FuturesNotificationServiceImpl(writer);

    @Test
    void TR27_nearLiquidation_namesTheLiquidationPriceAndTheMark() {
        UUID position = UUID.randomUUID();
        notifications.riskNotice(new FuturesRiskNotice(
                UUID.randomUUID(),
                FuturesRiskNotice.Kind.NEAR_LIQUIDATION,
                "BTCUSDT",
                "ISOLATED",
                position,
                new BigDecimal("94.00"),
                null,
                AT,
                new BigDecimal("90.40")));

        NewNotification written = written();
        assertThat(written.title()).isEqualTo("Near liquidation");
        assertThat(written.content())
                .isEqualTo("BTCUSDT isolated position is near its liquidation price 90.4 at mark 94. Add margin,"
                        + " reduce the position or set a stop loss.");
        assertThat(written.referenceType()).isEqualTo(NotificationReference.FUTURES_POSITION);
        assertThat(written.sourceKey()).isEqualTo("NEAR_LIQUIDATION:" + position + ":" + AT.toEpochMilli());
    }

    @Test
    void TR27_noStopLoss_asksForOne() {
        notifications.riskNotice(new FuturesRiskNotice(
                UUID.randomUUID(),
                FuturesRiskNotice.Kind.NO_STOP_LOSS,
                "ETHUSDT",
                "CROSSED",
                UUID.randomUUID(),
                null,
                null,
                AT));

        NewNotification written = written();
        assertThat(written.title()).isEqualTo("No stop loss");
        assertThat(written.content())
                .isEqualTo("ETHUSDT crossed position has no stop loss. Set one to limit the loss if the price turns.");
    }

    private NewNotification written() {
        ArgumentCaptor<NewNotification> captor = ArgumentCaptor.forClass(NewNotification.class);
        verify(writer).write(captor.capture());
        return captor.getValue();
    }
}
