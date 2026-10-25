package com.cryptopilot.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.news.event.NewsSourceFailing;
import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.user.UserApi;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The notice of NSF-10: every active administrator is told which source keeps failing and why, once per run of
 * failures and recipient.
 *
 * <p>Rule: NSF-10; NSF-15.
 */
class SystemNotificationServiceImplTest {

    private static final UUID SOURCE = UUID.fromString("019b76da-a800-7e0e-8000-000000000001");
    private static final UUID LOG = UUID.fromString("019b76da-a800-7e0e-8000-0000000000aa");
    private static final UUID FIRST_ADMIN = UUID.fromString("019b76da-a800-7d01-8000-0000000000a1");
    private static final UUID SECOND_ADMIN = UUID.fromString("019b76da-a800-7d01-8000-0000000000a2");

    private final NotificationWriter writer = mock(NotificationWriter.class);
    private final UserApi users = mock(UserApi.class);
    private final SystemNotificationServiceImpl service = new SystemNotificationServiceImpl(writer, users);

    @Test
    void NSF10_everyActiveAdministrator_isToldWhichSourceKeepsFailing() {
        when(users.activeAdministrators()).thenReturn(List.of(FIRST_ADMIN, SECOND_ADMIN));

        service.newsSourceFailing(failing("UNREACHABLE: did not answer within 30000 ms"));

        ArgumentCaptor<NewNotification> written = ArgumentCaptor.forClass(NewNotification.class);
        verify(writer, times(2)).write(written.capture());
        assertThat(written.getAllValues())
                .extracting(NewNotification::recipientId)
                .containsExactly(FIRST_ADMIN, SECOND_ADMIN);
        NewNotification first = written.getAllValues().getFirst();
        assertThat(first.type()).isEqualTo(NotificationType.SYSTEM);
        assertThat(first.title()).isEqualTo("A news source keeps failing");
        assertThat(first.content())
                .isEqualTo("CoinDesk Markets could not be read 5 times in a row."
                        + " Last error: UNREACHABLE: did not answer within 30000 ms");
        assertThat(first.referenceType()).isEqualTo(NotificationReference.NEWS_SOURCE);
        assertThat(first.referenceId()).isEqualTo(SOURCE);
        assertThat(first.sourceKey()).isEqualTo("NEWS_SOURCE_FAILING:" + LOG + ":" + FIRST_ADMIN);
        assertThat(first.sourceKey()).hasSizeLessThanOrEqualTo(128);
    }

    @Test
    void NSF10_aLongError_isCutInTheNotice_andAMissingOneIsEmpty() {
        when(users.activeAdministrators()).thenReturn(List.of(FIRST_ADMIN));

        service.newsSourceFailing(failing("e".repeat(1000)));
        service.newsSourceFailing(failing(null));

        ArgumentCaptor<NewNotification> written = ArgumentCaptor.forClass(NewNotification.class);
        verify(writer, times(2)).write(written.capture());
        assertThat(written.getAllValues().getFirst().content())
                .endsWith("e".repeat(SystemNotificationServiceImpl.MAX_ERROR_LENGTH) + "…");
        assertThat(written.getAllValues().get(1).content()).endsWith("Last error: ");
    }

    @Test
    void NSF10_withNoActiveAdministrator_nobodyIsTold() {
        when(users.activeAdministrators()).thenReturn(List.of());

        service.newsSourceFailing(failing("down"));

        verifyNoInteractions(writer);
    }

    private static NewsSourceFailing failing(String error) {
        return new NewsSourceFailing(SOURCE, "CoinDesk Markets", 5, LOG, error, Instant.parse("2026-10-25T10:00:00Z"));
    }
}
