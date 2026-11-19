package com.cryptopilot.notification.service.impl;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.notification.client.EmailMessage;
import com.cryptopilot.notification.config.MailDeliveryProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Writes the mail of a market incident to a Trader it affects (D-158): the words of their notice, a link to the market's
 * trading screen and how to stop these mails, in plain text and HTML.
 *
 * <p>Rule: NSF-03; NSF-15; D-158.
 */
@Component
public class MarketMails {

    private static final String STOP =
            "You receive this mail because you have orders, positions or plans on this market and notification mail"
                    + " is on in your profile. Turn it off there to stop these mails.";

    private static final String NOT_ADVICE =
            "CryptoPilot is a simulation: no real order is placed and no real funds are held.";

    private final MailDeliveryProperties properties;

    public MarketMails(MailDeliveryProperties properties) {
        this.properties = properties;
    }

    /** The mail of a notice titled {@code title} saying {@code content}, to {@code address}. */
    public EmailMessage notice(String address, MarketType market, String title, String content) {
        String link = UriComponentsBuilder.fromUri(properties.publicBaseUrl())
                .path(market == MarketType.SPOT ? "/trade/spot" : "/trade/futures")
                .build()
                .toUriString();
        String text = """
                %s

                %s

                Open the trading screen:
                %s

                %s

                %s
                """.formatted(title, content, link, STOP, NOT_ADVICE);
        String html = """
                <!DOCTYPE html>
                <html><body style="font-family: Arial, sans-serif; line-height: 1.5">
                <p><strong>%s</strong></p>
                <p>%s</p>
                <p><a href="%s">Open the trading screen</a></p>
                <p>%s</p>
                <p>%s</p>
                </body></html>
                """.formatted(
                        HtmlUtils.htmlEscape(title),
                        HtmlUtils.htmlEscape(content),
                        HtmlUtils.htmlEscape(link),
                        HtmlUtils.htmlEscape(STOP),
                        HtmlUtils.htmlEscape(NOT_ADVICE));
        return new EmailMessage(address, "CryptoPilot: " + title, text, html, link);
    }
}
