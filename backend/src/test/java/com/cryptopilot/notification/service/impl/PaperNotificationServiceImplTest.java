package com.cryptopilot.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.paper.event.PaperOrderNotice;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

/**
 * The notification of a paper order's end (TR-29): its kind by market, its words, what it opens and its source key.
 *
 * <p>Rule: TR-29; NSF-15.
 */
class PaperNotificationServiceImplTest {

    private final NotificationWriter writer = mock(NotificationWriter.class);
    private final PaperNotificationServiceImpl notifications = new PaperNotificationServiceImpl(writer);

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
            delimiter = ';',
            nullValues = "null",
            value = {
                "SPOT; FILLED; null; 2; 100.50; MARKET; Order filled; Your BTCUSDT buy limit order filled 2 at an"
                        + " average price of 100.5.",
                "FUTURES; FILLED; null; 0.5; null; FUTURES; Order filled; Your BTCUSDT buy limit order filled 0.5.",
                "FUTURES; TRIGGERED; null; 0; null; FUTURES; Order triggered; Your BTCUSDT buy limit order reached"
                        + " its trigger price and placed its order.",
                "SPOT; CANCELED; PAIR_NOT_TRADING; 0; null; MARKET; Order cancelled; Your BTCUSDT buy limit order was"
                        + " cancelled by the system: pair not trading.",
                "FUTURES; CANCELED; LIQUIDATION; 0.2; 99; FUTURES; Order cancelled; Your BTCUSDT buy limit order was"
                        + " cancelled by the system: liquidation. It had filled 0.2 before.",
                "FUTURES; EXPIRED; GOOD_TILL_DATE; 0; null; FUTURES; Order expired; Your BTCUSDT buy limit order"
                        + " expired: good till date."
            })
    void TR29_aNotice_isWrittenWithTheKindOfItsMarket_andOpensTheOrder(
            MarketType market,
            PaperOrderNotice.Kind kind,
            String reason,
            String executed,
            String avgPrice,
            NotificationType type,
            String title,
            String content) {
        UUID orderId = UUID.randomUUID();
        notifications.orderNotice(new PaperOrderNotice(
                UUID.randomUUID(),
                orderId,
                market,
                "BTCUSDT",
                "BUY",
                "LIMIT",
                kind,
                reason,
                new BigDecimal(executed),
                avgPrice == null ? null : new BigDecimal(avgPrice),
                Instant.parse("2026-11-22T08:00:00Z")));

        ArgumentCaptor<NewNotification> written = ArgumentCaptor.forClass(NewNotification.class);
        verify(writer).write(written.capture());
        NewNotification notification = written.getValue();
        assertThat(notification.type()).isEqualTo(type);
        assertThat(notification.title()).isEqualTo(title);
        assertThat(notification.content()).isEqualTo(content);
        assertThat(notification.referenceType()).isEqualTo(NotificationReference.PAPER_ORDER);
        assertThat(notification.referenceId()).isEqualTo(orderId);
        assertThat(notification.sourceKey()).isEqualTo("PAPER_ORDER_" + kind + ":" + orderId);
    }
}
