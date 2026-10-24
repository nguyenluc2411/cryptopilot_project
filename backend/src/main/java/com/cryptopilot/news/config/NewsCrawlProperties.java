package com.cryptopilot.news.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * When the crawl of NSF-10 runs and how it judges what it reads, under {@code cryptopilot.news.crawl}.
 *
 * <p>Rule: NSF-10 (every 5 minutes, due sources only; an administrator is told after 5 failures in a row); BR-48
 * (the same title within 48 hours is the same story).
 *
 * @param enabled whether the crawl is scheduled; off without a profile, so a test context crawls nothing
 * @param period the pause between the end of one run and the start of the next
 * @param initialDelay how long after start-up the first run waits
 * @param duplicateTitleWindow how far apart two publish times may be for equal titles to be one story
 * @param failureNoticeAfter how many failed crawls in a row of one source tell the administrators
 * @param coinNames what outlets call a coin, by its symbol, e.g. {@code BTC: [Bitcoin]}; the exchange names a coin
 *     after its symbol, so without these a headline that writes the name tags nothing
 */
@Validated
@ConfigurationProperties("cryptopilot.news.crawl")
public record NewsCrawlProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("5m") Duration period,
        @NotNull @DefaultValue("1m") Duration initialDelay,
        @NotNull @DefaultValue("48h") Duration duplicateTitleWindow,
        @Min(1) @DefaultValue("5") int failureNoticeAfter,
        Map<String, List<String>> coinNames) {

    public NewsCrawlProperties {
        Map<String, List<String>> bySymbol = new LinkedHashMap<>();
        if (coinNames != null) {
            coinNames.forEach((symbol, names) -> bySymbol.put(
                    symbol.strip().toUpperCase(Locale.ROOT), names == null ? List.of() : List.copyOf(names)));
        }
        coinNames = Map.copyOf(bySymbol);
    }

    /** The names configured for this symbol; none when it has none. */
    public List<String> namesOf(String symbol) {
        return coinNames.getOrDefault(symbol, List.of());
    }
}
