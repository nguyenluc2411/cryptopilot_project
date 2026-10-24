package com.cryptopilot.news.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

/**
 * The settings of a source hold BR-49 themselves, whatever built them.
 *
 * <p>Rule: BR-49; UC-49.
 */
class NewsSourceSettingsTest {

    @Test
    void BR49_fifteenMinutes_isTheShortestInterval() {
        assertThat(settings(15).crawlIntervalMinutes()).isEqualTo(15);
        assertThat(NewsSourceSettings.MIN_CRAWL_INTERVAL_MINUTES).isEqualTo(15);
        assertThatIllegalArgumentException().isThrownBy(() -> settings(14)).withMessageContaining("BR-49");
        assertThatIllegalArgumentException().isThrownBy(() -> settings(0));
    }

    @Test
    void UC49_theNameAndTheFeedAddress_areRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new NewsSourceSettings(null, "https://e.com/f", 15, true, null, null, null, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new NewsSourceSettings("Name", null, 15, true, null, null, null, null));
    }

    private static NewsSourceSettings settings(int interval) {
        return new NewsSourceSettings("Name", "https://e.com/f", interval, true, null, null, null, null);
    }
}
