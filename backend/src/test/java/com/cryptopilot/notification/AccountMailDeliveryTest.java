package com.cryptopilot.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import com.cryptopilot.auth.service.AuthService;
import com.cryptopilot.common.service.OutboxService;
import com.cryptopilot.support.TestcontainersConfig;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-056 and T-057 end to end: auth issues a link, the event publication registry records it in the same transaction,
 * the listener mails it after the commit through the SMTP adapter, and the outbox processor retries what failed.
 *
 * <p>The SMTP server is {@link RecordingMailSender}: the messages are real MIME messages, the transport is not. Like
 * the auth tests, nothing here is {@code @Transactional}, because what is under test is what commits.
 *
 * <p>Rule: BR-01, BR-04; NSF-15; A-18; SRS UC-01, UC-02, UC-04, UC-05.
 */
@SpringBootTest(
        properties = {
            "spring.mail.host=smtp.test.invalid",
            "cryptopilot.notification.mail.public-base-url=https://app.test",
            "cryptopilot.notification.mail.from=CryptoPilot <no-reply@cryptopilot.test>",
            "cryptopilot.outbox.first-retry-after=0s"
        })
@Import({TestcontainersConfig.class, AccountMailDeliveryTest.SmtpStub.class})
@ExtendWith(OutputCaptureExtension.class)
class AccountMailDeliveryTest {

    private static final String TEST_DOMAIN = "@t057.invalid";

    private static final String PASSWORD = "Abcdefg1";

    private static final Pattern VERIFY_LINK = Pattern.compile("https://app\\.test/verify-email\\?token=(\\S+)");

    private static final Pattern RESET_LINK = Pattern.compile("https://app\\.test/reset-password\\?token=(\\S+)");

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    private AuthService authService;

    @Autowired
    private OutboxService outbox;

    @Autowired
    private RecordingMailSender smtp;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void emptyTheMailboxAndTheOutbox() {
        smtp.clear();
        sql.sql("delete from event_publication").update();
    }

    @AfterEach
    void removeWhatTheTestWrote() {
        await().atMost(WAIT)
                .until(() -> unfinishedDeliveries() == 0 || !failedDeliveries().isEmpty());
        sql.sql("delete from event_publication").update();
        sql.sql("delete from user_account where email like :pattern")
                .param("pattern", "%" + TEST_DOMAIN)
                .update();
        smtp.clear();
    }

    // ------------------------------------------------------------------- mail

    /** UC-01, UC-02: one mail to the address registered, whose link verifies it; delivered, so no row is left. */
    @Test
    void UC01_registering_mailsOneLinkThatVerifiesTheAddress(CapturedOutput output) throws Exception {
        authService.register("verify" + TEST_DOMAIN, PASSWORD, "Verify Me");

        MimeMessage mail = awaitOneMail();
        assertThat(mail.getAllRecipients()).extracting(Object::toString).containsExactly("verify" + TEST_DOMAIN);
        assertThat(((InternetAddress) mail.getFrom()[0]).getAddress()).isEqualTo("no-reply@cryptopilot.test");
        assertThat(mail.getSubject()).isEqualTo("Verify your CryptoPilot email address");
        assertThat(RecordingMailSender.partOf(mail, "text/html")).contains("https://app.test/verify-email?token=");
        String token = tokenIn(mail, VERIFY_LINK);

        assertThatCode(() -> authService.verifyEmail(token)).doesNotThrowAnyException();
        assertThatCode(() -> authService.login("verify" + TEST_DOMAIN, PASSWORD, false))
                .doesNotThrowAnyException();
        await().atMost(WAIT).until(() -> unfinishedDeliveries() == 0);
        assertThat(output.getAll()).as("the SMTP path never logs the token").doesNotContain(token);
    }

    /** UC-04, UC-05: the reset mail's link sets the new password. */
    @Test
    void UC04_requestingAReset_mailsALinkThatSetsThePassword(CapturedOutput output) throws Exception {
        authService.register("reset" + TEST_DOMAIN, PASSWORD, "Reset Me");
        authService.verifyEmail(tokenIn(awaitOneMail(), VERIFY_LINK));
        smtp.clear();

        authService.requestPasswordReset("reset" + TEST_DOMAIN);

        MimeMessage mail = awaitOneMail();
        assertThat(mail.getAllRecipients()).extracting(Object::toString).containsExactly("reset" + TEST_DOMAIN);
        assertThat(mail.getSubject()).isEqualTo("Reset your CryptoPilot password");
        String token = tokenIn(mail, RESET_LINK);

        authService.resetPassword(token, "Zyxwvu9Q");
        assertThatCode(() -> authService.login("reset" + TEST_DOMAIN, "Zyxwvu9Q", false))
                .doesNotThrowAnyException();
        assertThat(output.getAll()).as("the SMTP path never logs the token").doesNotContain(token);
    }

    /** UC-01: a registration that rolls back leaves no publication and sends nothing. */
    @Test
    void UC01_aRegistrationThatRollsBack_mailsNothing() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> transactions.executeWithoutResult(status -> {
                    authService.register("silent" + TEST_DOMAIN, PASSWORD, "Silent");
                    throw new IllegalStateException("the caller fails after registering");
                }));

        assertThat(unfinishedDeliveries()).isZero();
        await().during(Duration.ofSeconds(1)).atMost(WAIT).until(() -> smtp.sent()
                .isEmpty());
    }

    // ------------------------------------------------------------------ outbox

    /** NSF-15: an SMTP failure keeps the publication; the processor's retry delivers it and the row goes. */
    @Test
    void NSF15_aFailedSend_staysInTheOutboxUntilARetryDelivers() throws Exception {
        smtp.failing(true);
        authService.register("retry" + TEST_DOMAIN, PASSWORD, "Retry Me");
        await().atMost(WAIT).until(() -> failedDeliveries().size() == 1);
        assertThat(smtp.sent()).isEmpty();

        smtp.failing(false);
        assertThat(outbox.retryFailedDeliveries()).isEqualTo(1);

        MimeMessage mail = awaitOneMail();
        assertThat(mail.getAllRecipients()).extracting(Object::toString).containsExactly("retry" + TEST_DOMAIN);
        await().atMost(WAIT).until(() -> unfinishedDeliveries() == 0);
        authService.verifyEmail(tokenIn(mail, VERIFY_LINK));
    }

    /** NSF-15: a delivery a crash left unfinished is counted as failed once stale, then retried. */
    @Test
    void NSF15_aDeliveryLeftUnfinishedByACrash_isRetried() throws Exception {
        failOneDelivery("crash");
        Timestamp longAgo = Timestamp.from(Instant.now().minus(Duration.ofMinutes(10)));
        sql.sql("update event_publication set status = 'PROCESSING', publication_date = :at,"
                        + " last_resubmission_date = :at")
                .param("at", longAgo)
                .update();

        assertThat(outbox.retryFailedDeliveries()).isEqualTo(1);

        assertThat(awaitOneMail().getAllRecipients())
                .extracting(Object::toString)
                .containsExactly("crash" + TEST_DOMAIN);
        await().atMost(WAIT).until(() -> unfinishedDeliveries() == 0);
    }

    /** NSF-15: a delivery that has used its three retries is left alone. */
    @Test
    void NSF15_aDeliveryOutOfRetries_isNotResubmitted() {
        failOneDelivery("exhausted");
        sql.sql("update event_publication set completion_attempts = 4").update();

        assertThat(outbox.retryFailedDeliveries()).isZero();

        assertThat(failedDeliveries()).hasSize(1);
        assertThat(smtp.sent()).isEmpty();
    }

    /** A-18: past the retention a publication is deleted, so its link does not stay in the table. */
    @Test
    void A18_anUndeliveredPublicationPastTheRetention_isDeleted() {
        failOneDelivery("expired");
        sql.sql("update event_publication set publication_date = :at")
                .param("at", Timestamp.from(Instant.now().minus(Duration.ofHours(25))))
                .update();

        assertThat(outbox.deleteExpired()).isEqualTo(1);

        assertThat(unfinishedDeliveries()).isZero();
    }

    /** A-18: a younger publication is kept for its retries. */
    @Test
    void A18_aPublicationWithinTheRetention_isKept() {
        failOneDelivery("young");

        assertThat(outbox.deleteExpired()).isZero();

        assertThat(failedDeliveries()).hasSize(1);
    }

    // ----------------------------------------------------------------- helpers

    private void failOneDelivery(String localPart) {
        smtp.failing(true);
        authService.register(localPart + TEST_DOMAIN, PASSWORD, "Someone");
        await().atMost(WAIT).until(() -> failedDeliveries().size() == 1);
        smtp.failing(false);
    }

    private MimeMessage awaitOneMail() {
        await().atMost(WAIT).until(() -> !smtp.sent().isEmpty());
        List<MimeMessage> sent = smtp.sent();
        assertThat(sent).hasSize(1);
        return sent.get(0);
    }

    private static String tokenIn(MimeMessage mail, Pattern link) throws MessagingException {
        String text = RecordingMailSender.partOf(mail, "text/plain");
        assertThat(text).isNotNull();
        Matcher matcher = link.matcher(text);
        assertThat(matcher.find()).as("the text carries the link").isTrue();
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }

    private long unfinishedDeliveries() {
        return sql.sql("select count(*) from event_publication")
                .query(Long.class)
                .single();
    }

    private List<String> failedDeliveries() {
        return sql.sql("select status from event_publication where status = 'FAILED'")
                .query(String.class)
                .list();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SmtpStub {

        @Bean
        RecordingMailSender mailSender() {
            return new RecordingMailSender();
        }
    }
}
