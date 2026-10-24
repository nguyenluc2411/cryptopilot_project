package com.cryptopilot.news.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cryptopilot.news.model.enums.CrawlStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The record of one crawl: a FAILED crawl read nothing and says why, the others carry both counts, and only a SUCCESS
 * has no error.
 *
 * <p>Rule: NSF-10; UC-49.
 */
class NewsCrawlLogTest {

    private static final UUID SOURCE = UUID.fromString("019b76da-a800-7e0e-8000-000000000001");
    private static final Instant START = Instant.parse("2026-10-25T10:00:00Z");
    private static final Instant END = START.plusSeconds(12);

    @Test
    void NSF10_aSuccessfulCrawl_carriesBothCountsAndNoError() {
        NewsCrawlLog log = NewsCrawlLog.record(SOURCE, START, END, CrawlStatus.SUCCESS, 30, 4, null);

        assertThat(log.getSourceId()).isEqualTo(SOURCE);
        assertThat(log.getStartedAt()).isEqualTo(START);
        assertThat(log.getFinishedAt()).isEqualTo(END);
        assertThat(log.getStatus()).isEqualTo(CrawlStatus.SUCCESS);
        assertThat(log.getArticlesFound()).isEqualTo(30);
        assertThat(log.getArticlesInserted()).isEqualTo(4);
        assertThat(log.getErrorMessage()).isNull();
        assertThat(NewsCrawlLog.record(SOURCE, START, START, CrawlStatus.SUCCESS, 0, 0, "  ")
                        .getErrorMessage())
                .isNull();
    }

    @Test
    void NSF10_aPartialCrawl_carriesTheCountsAndWhatWentWrong() {
        NewsCrawlLog log = NewsCrawlLog.record(SOURCE, START, END, CrawlStatus.PARTIAL, 30, 28, "  2 pages timed out ");

        assertThat(log.getArticlesInserted()).isEqualTo(28);
        assertThat(log.getErrorMessage()).isEqualTo("2 pages timed out");
    }

    @Test
    void NSF10_aFailedCrawl_hasNoCounts_andItsLongErrorIsCut() {
        NewsCrawlLog log = NewsCrawlLog.record(SOURCE, START, END, CrawlStatus.FAILED, null, null, "x".repeat(5000));

        assertThat(log.getArticlesFound()).isNull();
        assertThat(log.getArticlesInserted()).isNull();
        assertThat(log.getErrorMessage()).hasSize(NewsCrawlLog.MAX_ERROR_LENGTH);
    }

    @Test
    void NSF10_countsThatContradictTheStatus_areRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NewsCrawlLog.record(SOURCE, START, END, CrawlStatus.FAILED, 3, null, "down"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NewsCrawlLog.record(SOURCE, START, END, CrawlStatus.FAILED, null, 0, "down"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NewsCrawlLog.record(SOURCE, START, END, CrawlStatus.SUCCESS, null, null, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NewsCrawlLog.record(SOURCE, START, END, CrawlStatus.SUCCESS, 3, null, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NewsCrawlLog.record(SOURCE, START, END, CrawlStatus.SUCCESS, 3, 4, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NewsCrawlLog.record(SOURCE, START, END, CrawlStatus.SUCCESS, 3, -1, null));
    }

    @Test
    void NSF10_anErrorThatContradictsTheStatus_isRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NewsCrawlLog.record(SOURCE, START, END, CrawlStatus.SUCCESS, 3, 3, "oops"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NewsCrawlLog.record(SOURCE, START, END, CrawlStatus.PARTIAL, 3, 2, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NewsCrawlLog.record(SOURCE, START, END, CrawlStatus.FAILED, null, null, " "));
    }

    @Test
    void NSF10_aCrawlFinishesAfterItStarts_andNamesItsSourceTimesAndStatus() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NewsCrawlLog.record(SOURCE, END, START, CrawlStatus.SUCCESS, 1, 1, null));
        assertThatNullPointerException()
                .isThrownBy(() -> NewsCrawlLog.record(null, START, END, CrawlStatus.SUCCESS, 1, 1, null));
        assertThatNullPointerException()
                .isThrownBy(() -> NewsCrawlLog.record(SOURCE, null, END, CrawlStatus.SUCCESS, 1, 1, null));
        assertThatNullPointerException()
                .isThrownBy(() -> NewsCrawlLog.record(SOURCE, START, null, CrawlStatus.SUCCESS, 1, 1, null));
        assertThatNullPointerException().isThrownBy(() -> NewsCrawlLog.record(SOURCE, START, END, null, 1, 1, null));
    }
}
