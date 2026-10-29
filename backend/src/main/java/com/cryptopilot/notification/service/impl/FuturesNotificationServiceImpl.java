package com.cryptopilot.notification.service.impl;

import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.notification.service.FuturesNotificationService;
import com.cryptopilot.paper.event.FuturesRiskNotice;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The texts of the paper Futures notices. The SRS has no message for them, so they are written here in the style of
 * MSG23 and MSG24: one short sentence naming the pair and the figure. The source key is the kind, the position (or the
 * Trader for a cross call) and the time, so a delivery repeated by the outbox writes nothing, and a later call is told
 * again.
 *
 * <p>Rule: TR-43; NSF-15.
 */
@Service
@RequiredArgsConstructor
public class FuturesNotificationServiceImpl implements FuturesNotificationService {

    private final NotificationWriter writer;

    @Override
    @Transactional
    public void riskNotice(FuturesRiskNotice notice) {
        boolean cross = notice.positionId() == null;
        String subject = cross
                ? "Your cross positions"
                : notice.symbol() + " " + notice.marginType().toLowerCase();
        String title;
        String content;
        if (notice.kind() == FuturesRiskNotice.Kind.MARGIN_CALL) {
            title = "Margin call";
            content = subject + (cross ? " reached" : " position reached") + " a margin ratio of "
                    + percent(notice.marginRatio()) + "%" + (cross ? "" : " at mark " + plain(notice.price()))
                    + ". Add margin or reduce the position to avoid liquidation.";
        } else {
            title = "Position liquidated";
            content = subject + " position was liquidated at " + plain(notice.price()) + ".";
        }
        writer.write(new NewNotification(
                notice.userId(),
                NotificationType.FUTURES,
                title,
                content,
                cross ? null : NotificationReference.FUTURES_POSITION,
                notice.positionId(),
                notice.kind() + ":" + (cross ? notice.userId() : notice.positionId()) + ":"
                        + notice.at().toEpochMilli()));
    }

    private static String percent(BigDecimal ratio) {
        return ratio.movePointRight(2)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static String plain(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}
