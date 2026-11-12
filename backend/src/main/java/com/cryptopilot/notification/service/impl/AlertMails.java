package com.cryptopilot.notification.service.impl;

import com.cryptopilot.notification.client.EmailMessage;
import com.cryptopilot.notification.config.MailDeliveryProperties;
import com.cryptopilot.watchlist.event.AlertTriggered;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Writes the mail of an alert that fired (T-057): the words of its notification (MSG28), when it fired, a link to the
 * alert list (SCR-13) and how to stop these mails, in plain text and HTML. Like every message of the product it states
 * that an alert is information, not investment advice.
 *
 * <p>Rule: NSF-15, BR-62 ({@code EXTERNAL_ALERT_CHANNELS}); SRS 3.4.4.
 */
@Component
public class AlertMails {

    static final String ALERTS_ROUTE = "/alerts";

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private static final String STOP =
            "You receive this mail because email delivery is on for this alert. Turn it off in the alert,"
                    + " or turn off notification mail in your profile.";

    private static final String NOT_ADVICE = "Alerts are information, not investment advice.";

    private final MailDeliveryProperties properties;

    public AlertMails(MailDeliveryProperties properties) {
        this.properties = properties;
    }

    /** The mail of the trigger, to {@code address}. */
    public EmailMessage alert(String address, AlertTriggered event) {
        String link = UriComponentsBuilder.fromUri(properties.publicBaseUrl())
                .path(ALERTS_ROUTE)
                .build()
                .toUriString();
        String notice = AlertNotices.content(event);
        String fired = "Triggered at " + WHEN.format(event.triggeredAt()) + ".";
        String text = """
                %s

                %s

                See your alerts:
                %s

                %s

                %s
                """.formatted(notice, fired, link, STOP, NOT_ADVICE);
        String html = """
                <!DOCTYPE html>
                <html><body style="font-family: Arial, sans-serif; line-height: 1.5">
                <p><strong>%s</strong></p>
                <p>%s</p>
                <p><a href="%s">See your alerts</a></p>
                <p>%s</p>
                <p>%s</p>
                </body></html>
                """.formatted(
                        HtmlUtils.htmlEscape(notice),
                        HtmlUtils.htmlEscape(fired),
                        HtmlUtils.htmlEscape(link),
                        HtmlUtils.htmlEscape(STOP),
                        HtmlUtils.htmlEscape(NOT_ADVICE));
        return new EmailMessage(address, "CryptoPilot alert: " + AlertNotices.pair(event), text, html, link);
    }
}
