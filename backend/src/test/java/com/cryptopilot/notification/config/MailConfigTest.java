package com.cryptopilot.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.notification.RecordingMailSender;
import com.cryptopilot.notification.client.EmailSender;
import com.cryptopilot.notification.client.LoggingEmailSender;
import com.cryptopilot.notification.client.SmtpEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Which mail adapter starts (T-057): SMTP when a host is set, the log line when none is, and in the prod profile no
 * start at all without a host or with a sender that is not an address. The SMTP connection requires STARTTLS.
 *
 * <p>The tests named "configured" load the real {@code application.yml} and {@code application-prod.yml}, so what they
 * check is what a deployment gets from its environment variables.
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

    @Test
    void NSF15_aSenderThatIsNoAddress_refusesToStart() {
        for (String from : new String[] {"${SMTP_FROM}", "no-reply", "no-reply@", "a@b, c@d"}) {
            context.withPropertyValues(
                            "spring.mail.host=smtp.example.com", "cryptopilot.notification.mail.from=" + from)
                    .run(started -> assertThat(started)
                            .as(from)
                            .hasFailed()
                            .getFailure()
                            .rootCause()
                            .hasMessageContaining("SMTP_FROM is not a valid sender address"));
        }
    }

    @Test
    void NSF15_aSenderWithADisplayName_isAccepted() {
        context.withPropertyValues(
                        "spring.mail.host=smtp.example.com",
                        "cryptopilot.notification.mail.from=CryptoPilot <no-reply@cryptopilot.test>")
                .run(started -> assertThat(started.getBean(EmailSender.class)).isInstanceOf(SmtpEmailSender.class));
    }

    // ------------------------------------------------- the configuration files

    private final ApplicationContextRunner configured = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(MailConfig.class)
            .withBean(JavaMailSender.class, RecordingMailSender::new);

    @Test
    void NSF15_configuredProdWithoutSmtpFrom_refusesToStart() {
        configured
                .withPropertyValues(
                        "spring.profiles.active=prod", "SMTP_HOST=smtp.example.com", "PUBLIC_BASE_URL=https://app.test")
                .run(started -> assertThat(started)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("SMTP_FROM is not a valid sender address: '${SMTP_FROM}'"));
    }

    @Test
    void NSF15_configuredProdWithSmtpFrom_starts() {
        configured
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "SMTP_HOST=smtp.example.com",
                        "SMTP_FROM=no-reply@example.com",
                        "PUBLIC_BASE_URL=https://app.test")
                .run(started -> assertThat(started.getBean(EmailSender.class)).isInstanceOf(SmtpEmailSender.class));
    }

    private final ApplicationContextRunner configuredSmtp = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withPropertyValues("SMTP_HOST=smtp.example.com");

    @Test
    void NSF15_configuredSmtp_requiresStartTlsAndChecksTheServerName() {
        configuredSmtp.run(
                started -> assertThat(started.getBean(JavaMailSenderImpl.class).getJavaMailProperties())
                        .containsEntry("mail.smtp.starttls.enable", "true")
                        .containsEntry("mail.smtp.starttls.required", "true")
                        .containsEntry("mail.smtp.ssl.checkserveridentity", "true"));
    }

    @Test
    void NSF15_configuredSmtp_aLocalSinkMayTurnTheStartTlsRequirementOff() {
        configuredSmtp.withPropertyValues("SMTP_STARTTLS_REQUIRED=false").run(started -> assertThat(
                        started.getBean(JavaMailSenderImpl.class).getJavaMailProperties())
                .containsEntry("mail.smtp.starttls.required", "false")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true"));
    }
}
