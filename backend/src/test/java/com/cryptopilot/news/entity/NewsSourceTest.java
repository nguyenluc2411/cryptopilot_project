package com.cryptopilot.news.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cryptopilot.news.model.NewsSourceSettings;
import com.cryptopilot.news.model.enums.SourceType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A news source: created as RSS and never read, its settings replaced as a whole, and due once its interval has
 * elapsed (BR-49).
 *
 * <p>Rule: UC-49; BR-49; NSF-10.
 */
class NewsSourceTest {

    private static final UUID ADMIN = UUID.fromString("019b76da-a800-7d01-8000-0000000000aa");
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

    @Test
    void UC49_aNewSource_isRssNeverReadAndKeepsTheSettings() {
        NewsSource source = NewsSource.create(settings(30, true), ADMIN);

        assertThat(source.getSourceType()).isEqualTo(SourceType.RSS);
        assertThat(source.getLastCrawledAt()).isNull();
        assertThat(source.getManagedBy()).isEqualTo(ADMIN);
        assertThat(source.settings()).isEqualTo(settings(30, true));
        assertThat(source.isNew()).isTrue();
    }

    @Test
    void UC49_anUpdate_replacesEverySetting_keepsWhenItWasRead_andNamesTheAdministrator() {
        NewsSource source = NewsSource.create(settings(30, true), null);
        source.markCrawled(NOW);
        NewsSourceSettings changed = new NewsSourceSettings(
                "Renamed", "https://example.com/other", 45, false, "markets", "article", ".ad", "Agent/2");

        source.update(changed, ADMIN);

        assertThat(source.settings()).isEqualTo(changed);
        assertThat(source.getLastCrawledAt()).isEqualTo(NOW);
        assertThat(source.getManagedBy()).isEqualTo(ADMIN);
    }

    @Test
    void BR49_aSourceNeverRead_isDueAtOnce() {
        assertThat(NewsSource.create(settings(15, true), ADMIN).isDue(NOW)).isTrue();
    }

    @Test
    void BR49_aSourceRead_isDueExactlyOneIntervalLater_andNotBefore() {
        NewsSource source = NewsSource.create(settings(15, true), ADMIN);
        source.markCrawled(NOW);

        assertThat(source.isDue(NOW)).isFalse();
        assertThat(source.isDue(NOW.plusSeconds(15 * 60 - 1))).isFalse();
        assertThat(source.isDue(NOW.plusSeconds(15 * 60))).isTrue();
        assertThat(source.isDue(NOW.plusSeconds(3600))).isTrue();
    }

    @Test
    void BR49_aSourceSwitchedOff_isNeverDue() {
        NewsSource source = NewsSource.create(settings(15, false), ADMIN);

        assertThat(source.isDue(NOW)).isFalse();
        source.markCrawled(NOW.minusSeconds(86_400));
        assertThat(source.isDue(NOW)).isFalse();
    }

    @Test
    void BR49_theInstantsAreRequired() {
        NewsSource source = NewsSource.create(settings(15, true), ADMIN);

        assertThatNullPointerException().isThrownBy(() -> source.isDue(null));
        assertThatNullPointerException().isThrownBy(() -> source.markCrawled(null));
        assertThatNullPointerException().isThrownBy(() -> NewsSource.create(null, ADMIN));
    }

    private static NewsSourceSettings settings(int interval, boolean active) {
        return new NewsSourceSettings(
                "Example Markets", "https://example.com/feed", interval, active, null, "div.body", null, null);
    }
}
