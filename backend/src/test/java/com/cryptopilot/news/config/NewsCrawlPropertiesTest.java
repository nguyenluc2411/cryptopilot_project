package com.cryptopilot.news.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The crawl settings: the defaults of NSF-10 and BR-48 with nothing set, and coin names found by symbol whatever case
 * the configuration writes the symbol in.
 *
 * <p>Rule: NSF-10; BR-48.
 */
class NewsCrawlPropertiesTest {

    @Test
    void NSF10_withNothingSet_theDefaultsAreThoseOfTheSrs() {
        NewsCrawlProperties properties = bind(Map.of());

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.period()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.duplicateTitleWindow()).isEqualTo(Duration.ofHours(48));
        assertThat(properties.failureNoticeAfter()).isEqualTo(5);
        assertThat(properties.namesOf("BTC")).isEmpty();
    }

    @Test
    void NSF10_coinNames_areFoundByTheUpperCaseSymbol() {
        Map<String, List<String>> names = new HashMap<>();
        names.put(" eth ", List.of("Ethereum", "Ether"));
        names.put("BTC", null);
        NewsCrawlProperties properties = new NewsCrawlProperties(
                true, Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofHours(48), 5, names);

        assertThat(properties.namesOf("ETH")).containsExactly("Ethereum", "Ether");
        assertThat(properties.namesOf("BTC")).isEmpty();
        assertThat(properties.namesOf("eth"))
                .as("the lookup is by the stored symbol")
                .isEmpty();
    }

    @Test
    void NSF10_theApplicationsList_bindsAsWritten() {
        NewsCrawlProperties properties = bind(Map.of(
                "cryptopilot.news.crawl.coin-names.BTC[0]", "Bitcoin",
                "cryptopilot.news.crawl.coin-names.BCH[0]", "Bitcoin Cash",
                "cryptopilot.news.crawl.failure-notice-after", "3"));

        assertThat(properties.namesOf("BTC")).containsExactly("Bitcoin");
        assertThat(properties.namesOf("BCH")).containsExactly("Bitcoin Cash");
        assertThat(properties.failureNoticeAfter()).isEqualTo(3);
    }

    private static NewsCrawlProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("cryptopilot.news.crawl", NewsCrawlProperties.class);
    }
}
