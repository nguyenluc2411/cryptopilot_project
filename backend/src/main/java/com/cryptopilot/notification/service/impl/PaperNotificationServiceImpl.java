package com.cryptopilot.notification.service.impl;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.notification.service.PaperNotificationService;
import com.cryptopilot.paper.event.PaperOrderNotice;
import java.math.BigDecimal;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the notification of a paper order's end (TR-29). A Futures order is a {@code FUTURES} notification, as the
 * Futures risk notices are; a Spot order a {@code MARKET} one, the kind that tells of the trading on a market (D-159):
 * the clients already know both kinds. It opens the order ({@code PAPER_ORDER}); the source key is the order and how it
 * ended, so a notice delivered twice notifies once. Pushed to the Trader's devices like every notification (T-058).
 *
 * <p>Rule: TR-29; NSF-15.
 */
@Service
@RequiredArgsConstructor
public class PaperNotificationServiceImpl implements PaperNotificationService {

    private final NotificationWriter writer;

    @Override
    @Transactional
    public void orderNotice(PaperOrderNotice notice) {
        String order = notice.symbol() + " " + words(notice.side()) + " " + words(notice.orderType()) + " order";
        String title;
        String content;
        switch (notice.kind()) {
            case FILLED -> {
                title = "Order filled";
                content = "Your " + order + " filled " + plain(notice.executedQuantity())
                        + (notice.avgPrice() == null ? "" : " at an average price of " + plain(notice.avgPrice()))
                        + ".";
            }
            case TRIGGERED -> {
                title = "Order triggered";
                content = "Your " + order + " reached its trigger price and placed its order.";
            }
            case CANCELED -> {
                title = "Order cancelled";
                content = "Your " + order + " was cancelled by the system: " + reason(notice.reason()) + "."
                        + filledSoFar(notice);
            }
            case EXPIRED -> {
                title = "Order expired";
                content = "Your " + order + " expired: " + reason(notice.reason()) + "." + filledSoFar(notice);
            }
            default -> throw new IllegalStateException("unknown notice " + notice.kind());
        }
        writer.write(new NewNotification(
                notice.userId(),
                notice.market() == MarketType.FUTURES ? NotificationType.FUTURES : NotificationType.MARKET,
                title,
                content,
                NotificationReference.PAPER_ORDER,
                notice.orderId(),
                "PAPER_ORDER_" + notice.kind() + ":" + notice.orderId()));
    }

    private static String filledSoFar(PaperOrderNotice notice) {
        return notice.executedQuantity().signum() > 0
                ? " It had filled " + plain(notice.executedQuantity()) + " before."
                : "";
    }

    /** A status reason in words, e.g. {@code PAIR_NOT_TRADING} as "pair not trading". */
    private static String reason(String code) {
        return code == null ? "a reason of the system" : words(code);
    }

    private static String words(String code) {
        return code.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private static String plain(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}
