package com.cryptopilot.news.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.news.model.enums.NewsPeriod;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The three periods of the news feed as a request writes them, and the one a request that names none gets.
 *
 * <p>Rule: UC-34; SRS 3.8.1.
 */
class NewsPeriodTest {

    @Test
    void UC34_thePeriodsAreWrittenAs24h7dAnd30d_inAnyCase() {
        assertThat(NewsPeriod.ofCode("24h")).contains(NewsPeriod.LAST_24_HOURS);
        assertThat(NewsPeriod.ofCode(" 7D ")).contains(NewsPeriod.LAST_7_DAYS);
        assertThat(NewsPeriod.ofCode("30d")).contains(NewsPeriod.LAST_30_DAYS);
        assertThat(NewsPeriod.ofCode("1y")).isEmpty();
        assertThat(NewsPeriod.LAST_30_DAYS.code()).isEqualTo("30d");
        assertThat(NewsPeriod.LAST_24_HOURS.length()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void UC34_aFeedThatNamesNoPeriod_readsSevenDays() {
        assertThat(NewsPeriod.DEFAULT).isEqualTo(NewsPeriod.LAST_7_DAYS);
        assertThat(NewsPeriod.DEFAULT.length()).isEqualTo(Duration.ofDays(7));
    }
}
