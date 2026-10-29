package com.cryptopilot.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.watchlist.event.AlertTriggered;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import jakarta.mail.internet.MimeMessage;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-056 and T-057 for alerts, end to end: a trigger commits with its {@link AlertTriggered}, the in-app listener writes
 * MSG28 for the owner, and the mail listener mails it only to a Trader whose plan includes external alert channels and
 * whose account takes notification mail. The SMTP server is {@link RecordingMailSender}. Nothing here is
 * {@code @Transactional}, because what is under test is what commits.
 *
 * <p>Rule: NSF-06, NSF-15, BR-62 ({@code EXTERNAL_ALERT_CHANNELS}); SRS 3.2.5, 3.4.4, 3.4.5.
 */
@SpringBootTest(
        properties = {
            "spring.mail.host=smtp.test.invalid",
            "cryptopilot.notification.mail.public-base-url=https://app.test",
            "cryptopilot.notification.mail.from=CryptoPilot <no-reply@cryptopilot.test>",
            "cryptopilot.outbox.first-retry-after=0s"
        })
@Import({TestcontainersConfig.class, AlertNotificationDeliveryTest.SmtpStub.class})
class AlertNotificationDeliveryTest {

    private static final UUID PRO_PACKAGE = UUID.fromString("019b76da-a800-7002-8000-000000000003");

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private RecordingMailSender smtp;

    @Autowired
    private JdbcClient sql;

    private final List<UUID> accounts = new ArrayList<>();

    @BeforeEach
    void emptyTheMailboxAndTheOutbox() {
        smtp.clear();
        sql.sql("delete from event_publication").update();
    }

    @AfterEach
    void removeWhatTheTestWrote() {
        await().atMost(WAIT).until(() -> unfinishedDeliveries() == 0);
        for (UUID account : accounts) {
            sql.sql("delete from notification where user_id = ?").param(account).update();
            sql.sql("""
                            delete from user_subscription
                             where order_id in (select order_id from subscription_order where user_id = ?)""").param(account).update();
            sql.sql("delete from subscription_order where user_id = ?")
                    .param(account)
                    .update();
            sql.sql("delete from user_profile where user_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        sql.sql("delete from event_publication").update();
    }

    @Test
    void NSF15_aProTraderWhoseAlertAsksForMail_getsTheNotificationAndTheMail() throws Exception {
        UUID trader = trader(true, true);
        subscribeToPro(trader);

        AlertTriggered event = fire(trader, true);

        Map<String, Object> notification = awaitNotification(event);
        assertThat(notification)
                .containsEntry("notification_type", "ALERT")
                .containsEntry("title", "Price alert")
                .containsEntry("content", "BTCUSDT Spot: price crossed above 65000 (65012.5).")
                .containsEntry("reference_type", "ALERT")
                .containsEntry("reference_id", event.alertId());
        await().atMost(WAIT).until(() -> smtp.sent().size() == 1);
        MimeMessage mail = smtp.sent().get(0);
        assertThat(mail.getAllRecipients()[0].toString()).isEqualTo(address(trader));
        assertThat(mail.getSubject()).isEqualTo("CryptoPilot alert: BTCUSDT Spot");
        assertThat(text(mail))
                .contains("BTCUSDT Spot: price crossed above 65000 (65012.5).")
                .contains("https://app.test/alerts");
    }

    @Test
    void BR62_aFreeTraderWhoseAlertAsksForMail_getsTheNotificationOnly() {
        UUID trader = trader(true, true);

        AlertTriggered event = fire(trader, true);

        awaitNotification(event);
        await().atMost(WAIT).until(() -> unfinishedDeliveries() == 0);
        assertThat(smtp.sent()).isEmpty();
    }

    @Test
    void NSF15_anUnverifiedAddressOrNotificationMailSwitchedOff_isNotMailed() {
        UUID unverified = trader(false, true);
        UUID mailOff = trader(true, false);
        subscribeToPro(unverified);
        subscribeToPro(mailOff);

        AlertTriggered first = fire(unverified, true);
        AlertTriggered second = fire(mailOff, true);

        awaitNotification(first);
        awaitNotification(second);
        await().atMost(WAIT).until(() -> unfinishedDeliveries() == 0);
        assertThat(smtp.sent()).isEmpty();
    }

    @Test
    void NSF15_theSameTriggerDeliveredTwice_isOneNotification() {
        UUID trader = trader(true, true);
        AlertTriggered event = fire(trader, false);
        awaitNotification(event);

        transactions.executeWithoutResult(status -> events.publishEvent(event));

        await().atMost(WAIT).until(() -> unfinishedDeliveries() == 0);
        assertThat(sql.sql("select count(*) from notification where user_id = ?")
                        .param(trader)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    private AlertTriggered fire(UUID trader, boolean notifyEmail) {
        AlertTriggered event = new AlertTriggered(
                UUID.randomUUID(),
                trader,
                UUID.randomUUID(),
                "BTCUSDT",
                MarketType.SPOT,
                AlertType.PRICE,
                null,
                ConditionOperator.CROSS_ABOVE,
                new BigDecimal("65000.00"),
                new BigDecimal("65012.50"),
                Instant.now(),
                1,
                true,
                notifyEmail,
                false);
        transactions.executeWithoutResult(status -> events.publishEvent(event));
        return event;
    }

    private Map<String, Object> awaitNotification(AlertTriggered event) {
        String key = "ALERT_TRIGGERED:" + event.alertId() + ":" + event.triggerCount();
        await().atMost(WAIT)
                .until(() -> sql.sql("select count(*) from notification where source_key = ?")
                                .param(key)
                                .query(Long.class)
                                .single()
                        == 1L);
        return sql.sql("""
                        select notification_type, title, content, reference_type, reference_id
                          from notification where source_key = ?""").param(key).query().singleRow();
    }

    private UUID trader(boolean verified, boolean notificationMail) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status,
                                                  email_verified_at, created_at, updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?, ?)""").params(id, address(id), verified ? now : null, now, now).update();
        sql.sql("""
                        insert into user_profile (user_id, display_name, notify_email, created_at, updated_at)
                        values (?, 'Alert trader', ?, ?, ?)""").params(id, notificationMail, now, now).update();
        accounts.add(id);
        return id;
    }

    /** A PAID order for the PRO package and an ACTIVE subscription that started a day ago. */
    private void subscribeToPro(UUID trader) {
        Instant start = Instant.now().minus(Duration.ofDays(1));
        UUID order = UUID.randomUUID();
        sql.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, created_at, paid_at, expires_at,
                                                        updated_at)
                        values (?, ?, ?, ?, 1, 'VND', 'VNPAY', 'PAID', ?, ?, ?, ?)""")
                .params(
                        order,
                        trader,
                        PRO_PACKAGE,
                        "T056A-" + order,
                        Timestamp.from(start),
                        Timestamp.from(start),
                        Timestamp.from(start.plusSeconds(900)),
                        Timestamp.from(start))
                .update();
        sql.sql("""
                        insert into user_subscription (subscription_id, order_id, start_at, end_at,
                                                       subscription_status, created_at, updated_at)
                        values (?, ?, ?, ?, 'ACTIVE', ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        order,
                        Timestamp.from(start),
                        Timestamp.from(start.plus(Duration.ofDays(30))),
                        Timestamp.from(start),
                        Timestamp.from(start))
                .update();
    }

    private static String address(UUID trader) {
        return trader + "@t056a.invalid";
    }

    private static String text(MimeMessage mail) {
        return RecordingMailSender.partOf(mail, "text/plain");
    }

    private long unfinishedDeliveries() {
        return sql.sql("select count(*) from event_publication")
                .query(Long.class)
                .single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SmtpStub {

        @Bean
        RecordingMailSender mailSender() {
            return new RecordingMailSender();
        }
    }
}
