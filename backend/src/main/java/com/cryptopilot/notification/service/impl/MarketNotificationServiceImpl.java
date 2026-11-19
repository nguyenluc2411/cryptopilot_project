package com.cryptopilot.notification.service.impl;

import com.cryptopilot.market.event.MarketPauseAnnounced;
import com.cryptopilot.market.event.MarketRecoveryOverdue;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.notification.service.MarketNotificationService;
import com.cryptopilot.paper.event.PaperIncidentNotice;
import com.cryptopilot.trading.event.PlanIncidentNotice;
import com.cryptopilot.user.UserApi;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The texts of a market incident's notices (D-158). The pause notice of a Trader is keyed by the incident and the
 * Trader only, so paper trading and plans on the same market write it once; the reopening notices are keyed apart, as
 * they say different things. Times are given in UTC, as the client localises the notice's own time.
 *
 * <p>Rule: NSF-03; NSF-15; D-158.
 */
@Service
@RequiredArgsConstructor
public class MarketNotificationServiceImpl implements MarketNotificationService {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final NotificationWriter writer;
    private final UserApi users;

    @Override
    @Transactional
    public void paperIncident(PaperIncidentNotice notice) {
        if (notice.kind() == PaperIncidentNotice.Kind.PAUSED) {
            paused(notice.userId(), notice.market(), notice.incidentId(), notice.lastLiveAt(), notice.message());
            return;
        }
        StringBuilder content = new StringBuilder(name(notice.market()))
                .append(" trading reopened after a pause from ")
                .append(TIME.format(notice.lastLiveAt()))
                .append(" to ")
                .append(TIME.format(notice.resumedAt()))
                .append(". ");
        if (notice.replayedFills() == 0 && notice.liquidations() == 0) {
            content.append("None of your orders or positions changed meanwhile.");
        } else {
            content.append("While it caught up, at the prices the exchange traded meanwhile: ");
            if (notice.replayedFills() > 0) {
                content.append(count(notice.replayedFills(), "fill")).append(" of your orders");
            }
            if (notice.liquidations() > 0) {
                content.append(notice.replayedFills() > 0 ? ", and " : "")
                        .append(count(notice.liquidations(), "position"))
                        .append(" liquidated");
            }
            content.append('.');
        }
        if (notice.market() == MarketType.FUTURES) {
            content.append(" Funding due in that period is settled at the exchange's rates.");
        }
        writer.write(new NewNotification(
                notice.userId(),
                NotificationType.MARKET,
                name(notice.market()) + " trading resumed",
                content.toString(),
                null,
                null,
                "MARKET_RESUMED:" + notice.incidentId() + ":" + notice.userId() + ":PAPER"));
    }

    @Override
    @Transactional
    public void planIncident(PlanIncidentNotice notice) {
        if (!notice.resumed()) {
            paused(notice.userId(), notice.market(), notice.incidentId(), notice.lastLiveAt(), notice.message());
            return;
        }
        writer.write(new NewNotification(
                notice.userId(),
                NotificationType.MARKET,
                "Plans on " + name(notice.market()) + " watched again",
                "Your plans on " + name(notice.market()) + " are watched again after a pause from "
                        + TIME.format(notice.lastLiveAt()) + " to " + TIME.format(notice.resumedAt())
                        + ". An entry or exit reached meanwhile was applied at the exchange's prices and told"
                        + " separately.",
                null,
                null,
                "MARKET_RESUMED:" + notice.incidentId() + ":" + notice.userId() + ":PLANS"));
    }

    @Override
    @Transactional
    public void pauseAnnounced(MarketPauseAnnounced announced) {
        String content = name(announced.market()) + " trading is closed since " + TIME.format(announced.lastLiveAt())
                + ": " + reason(announced.reason())
                + (announced.message() == null ? "." : ". " + announced.message())
                + " It reopens by itself once the exchange data is back and caught up.";
        for (UUID admin : users.activeAdministrators()) {
            writer.write(new NewNotification(
                    admin,
                    NotificationType.SYSTEM,
                    name(announced.market()) + " trading paused",
                    content,
                    null,
                    null,
                    "MARKET_PAUSED_ADMIN:" + announced.incidentId() + ":" + admin));
        }
    }

    @Override
    @Transactional
    public void recoveryOverdue(MarketRecoveryOverdue overdue) {
        String content = overdue.beyondReplay()
                ? name(overdue.market()) + " exchange data was lost since " + TIME.format(overdue.lastLiveAt())
                        + ", longer than can be replayed. Trading stays closed until it is reopened by hand in the"
                        + " market health console."
                : name(overdue.market()) + " is still catching up on the exchange data lost since "
                        + TIME.format(overdue.lastLiveAt()) + ", recovering since "
                        + TIME.format(overdue.recoveringSince()) + ". Trading stays closed until it has.";
        for (UUID admin : users.activeAdministrators()) {
            writer.write(new NewNotification(
                    admin,
                    NotificationType.SYSTEM,
                    name(overdue.market()) + " recovery needs attention",
                    content,
                    null,
                    null,
                    "MARKET_OVERDUE_ADMIN:" + overdue.incidentId() + ":" + admin));
        }
    }

    private void paused(UUID userId, MarketType market, UUID incidentId, Instant lastLiveAt, String message) {
        String content = message != null
                ? name(market) + " trading is under maintenance: " + message
                        + " Your orders, positions and plans are kept as they are."
                : "Exchange data for " + name(market) + " has been unavailable since " + TIME.format(lastLiveAt)
                        + ". Your orders, positions and plans are kept: nothing is filled, liquidated or charged"
                        + " funding until the data is back, then the period is applied as the exchange traded.";
        writer.write(new NewNotification(
                userId,
                NotificationType.MARKET,
                name(market) + " trading paused",
                content,
                null,
                null,
                "MARKET_PAUSED:" + incidentId + ":" + userId));
    }

    private static String reason(IncidentReason reason) {
        return switch (reason) {
            case STREAM_LOST -> "the exchange stream sends nothing";
            case REST_UNAVAILABLE -> "the exchange stream and API do not answer";
            case RATE_LIMITED -> "the exchange refuses the calls for their weight";
            case IP_BANNED -> "the exchange banned the server's address";
            case MAINTENANCE -> "maintenance";
        };
    }

    private static String name(MarketType market) {
        return market == MarketType.SPOT ? "Spot" : "Futures";
    }

    private static String count(long amount, String noun) {
        return amount + " " + noun + (amount == 1 ? "" : "s");
    }
}
