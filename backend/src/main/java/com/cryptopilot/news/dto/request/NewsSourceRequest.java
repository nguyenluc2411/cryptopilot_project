package com.cryptopilot.news.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Everything an administrator sets on a news source in SCR-41, for a new source or replacing an existing one as a
 * whole (UC-49). The feed is read once before the source is saved, and the selectors are parsed; blank optional values
 * are stored as none.
 *
 * <p>Rule: UC-49; BR-49.
 *
 * @param sourceName the name shown in the console and on each article, at most 100 characters
 * @param feedUrl the http or https address of the RSS or Atom feed, unique among sources
 * @param crawlIntervalMinutes how often the feed is read, at least 15 minutes (BR-49)
 * @param active whether the crawl reads the source
 * @param categoryFilter the feed categories an item must carry to be kept, comma separated; none keeps every item
 * @param bodySelector the CSS selectors of the article body on the outlet's pages, comma separated, the first that
 *     matches wins; none keeps to what the feed carries
 * @param excludedSelectors CSS selectors removed from the body before it is read
 * @param userAgent the User-Agent sent to this outlet, printable ASCII; none sends the default
 */
public record NewsSourceRequest(
        @NotBlank(message = "MSG01") @Size(max = 100, message = "MSG15")
        String sourceName,

        @NotBlank(message = "MSG01") @Size(max = 1000, message = "MSG15")
        String feedUrl,

        @NotNull(message = "MSG01") @Min(value = 15, message = "MSG15")
        Integer crawlIntervalMinutes,

        @NotNull(message = "MSG01") Boolean active,

        @Size(max = 256, message = "MSG15") String categoryFilter,

        @Size(max = 512, message = "MSG15") String bodySelector,

        @Size(max = 1024, message = "MSG15") String excludedSelectors,

        @Size(max = 256, message = "MSG15") @Pattern(regexp = "[\\x20-\\x7E]*", message = "MSG15")
        String userAgent) {}
