package com.cryptopilot.common.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cryptopilot.common.config.OutboxProperties;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.EventPublication.Status;

/**
 * When a failed delivery is retried: after 1, 2 and 4 times the first wait, measured from the last attempt, three
 * times at most, and never while it is not failed. The database side is in {@code AccountMailDeliveryTest}.
 *
 * <p>Rule: NSF-15; ADR-010.
 */
class OutboxServiceImplTest {

    private static final Instant PUBLISHED = Instant.parse("2026-10-17T10:00:00Z");

    private static final OutboxProperties PROPERTIES = new OutboxProperties(
            true, Duration.ofMinutes(1), Duration.ofMinutes(1), 3, Duration.ofMinutes(5), Duration.ofHours(24));

    @Test
    void NSF15_aFirstFailure_isRetriedOneWaitAfterThePublication() {
        EventPublication failed = publication(Status.FAILED, 1, null);

        assertThat(OutboxServiceImpl.isDue(failed, PUBLISHED.plusSeconds(59), PROPERTIES))
                .isFalse();
        assertThat(OutboxServiceImpl.isDue(failed, PUBLISHED.plusSeconds(60), PROPERTIES))
                .isTrue();
    }

    @Test
    void NSF15_eachRetry_waitsTwiceAsLongAfterTheLastAttempt() {
        Instant lastAttempt = PUBLISHED.plus(Duration.ofMinutes(3));

        EventPublication secondFailure = publication(Status.FAILED, 2, lastAttempt);
        assertThat(OutboxServiceImpl.isDue(secondFailure, lastAttempt.plusSeconds(119), PROPERTIES))
                .isFalse();
        assertThat(OutboxServiceImpl.isDue(secondFailure, lastAttempt.plusSeconds(120), PROPERTIES))
                .isTrue();

        EventPublication thirdFailure = publication(Status.FAILED, 3, lastAttempt);
        assertThat(OutboxServiceImpl.isDue(thirdFailure, lastAttempt.plusSeconds(239), PROPERTIES))
                .isFalse();
        assertThat(OutboxServiceImpl.isDue(thirdFailure, lastAttempt.plusSeconds(240), PROPERTIES))
                .isTrue();
    }

    @Test
    void NSF15_afterThreeRetries_theDeliveryIsLeftAlone() {
        EventPublication exhausted = publication(Status.FAILED, 4, PUBLISHED);

        assertThat(OutboxServiceImpl.isDue(exhausted, PUBLISHED.plus(Duration.ofDays(1)), PROPERTIES))
                .isFalse();
    }

    @Test
    void NSF15_aDeliveryNotFailed_isNeverResubmitted() {
        Instant muchLater = PUBLISHED.plus(Duration.ofDays(1));

        for (Status status : new Status[] {Status.PUBLISHED, Status.PROCESSING, Status.RESUBMITTED, Status.COMPLETED}) {
            assertThat(OutboxServiceImpl.isDue(publication(status, 1, null), muchLater, PROPERTIES))
                    .as(status.name())
                    .isFalse();
        }
    }

    @Test
    void NSF15_aRowWithoutAnAttemptCount_countsAsItsFirstAttempt() {
        EventPublication legacy = publication(Status.FAILED, 0, null);

        assertThat(OutboxServiceImpl.isDue(legacy, PUBLISHED.plusSeconds(60), PROPERTIES))
                .isTrue();
    }

    @Test
    void NSF15_noRetries_meansAFailureIsNeverResubmitted() {
        OutboxProperties none = new OutboxProperties(
                true, Duration.ofMinutes(1), Duration.ofMinutes(1), 0, Duration.ofMinutes(5), Duration.ofHours(24));

        assertThat(OutboxServiceImpl.isDue(publication(Status.FAILED, 1, null), PUBLISHED.plusSeconds(3600), none))
                .isFalse();
    }

    private static EventPublication publication(Status status, int attempts, Instant lastResubmission) {
        EventPublication publication = mock(EventPublication.class);
        when(publication.getStatus()).thenReturn(status);
        when(publication.getCompletionAttempts()).thenReturn(attempts);
        when(publication.getLastResubmissionDate()).thenReturn(lastResubmission);
        when(publication.getPublicationDate()).thenReturn(PUBLISHED);
        return publication;
    }
}
