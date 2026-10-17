package com.cryptopilot.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.notification.RecordingMailSender;
import com.cryptopilot.notification.client.EmailSender;
import com.cryptopilot.notification.client.LoggingEmailSender;
import com.cryptopilot.notification.client.SmtpEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Which mail adapter starts (T-057): SMTP when a host is set, the log line when none is, and in the prod profile no
 * start at all without a host.
 *
 * <p>Rule: NSF-15; BR-01, BR-04.
 */
class MailConfigTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(MailConfig.class)
            .withBean(JavaMailSender.class, RecordingMailSender::new)
            .withPropertyValues(
                    "cryptopilot.notification.mail.from=no-reply@cryptopilot.test",
                    "cryptopilot.notification.mail.public-base-url=https://app.test");

    @Test
    void NSF15_aHost_selectsTheSmtpAdapter() {
        context.withPropertyValues("spring.mail.host=smtp.example.com", "spring.profiles.active=prod")
                .run(started -> assertThat(started.getBean(EmailSender.class)).isInstanceOf(SmtpEmailSender.class));
    }

    @Test
    void NSF15_noHostOutsideProd_selectsTheLogAdapter() {
        context.withPropertyValues("spring.mail.host=")
                .run(started -> assertThat(started.getBean(EmailSender.class)).isInstanceOf(LoggingEmailSender.class));
        context.withPropertyValues("spring.profiles.active=dev")
                .run(started -> assertThat(started.getBean(EmailSender.class)).isInstanceOf(LoggingEmailSender.class));
    }

    @Test
    void NSF15_noHostInProd_refusesToStart() {
        context.withPropertyValues("spring.mail.host=", "spring.profiles.active=prod")
                .run(started -> assertThat(started)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("SMTP_HOST is not set"));
    }

    @Test
    void NSF15_noSender_refusesToStart() {
        context.withPropertyValues("spring.mail.host=smtp.example.com", "cryptopilot.notification.mail.from=")
                .run(started -> assertThat(started).hasFailed());
    }
}
