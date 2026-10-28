package com.cryptopilot.community.dto.request;

import com.cryptopilot.community.model.enums.OfficialCategory;
import com.cryptopilot.community.model.enums.PostSentiment;
import com.cryptopilot.market.model.enums.MarketType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The form of SCR-26: a new post, or the new content of an existing one (UC-27, UC-29, UC-44). Leading and trailing
 * white space is removed before the lengths are checked, so a title of five spaces is no title.
 *
 * <p>Rule: UC-27, UC-29, UC-44; BR-44; SRS 3.7.2.
 *
 * @param title 5 to 150 characters
 * @param content 1 to 10 000 characters
 * @param marketType the market the post is about, or {@code null} for none
 * @param coins the symbols of 0 to 5 coins a listed pair trades, e.g. {@code BTC}; case is ignored and a repeated
 *     symbol counts once; {@code null} for none
 * @param media the uploaded files in the order they are shown: up to 4 images or 1 video (BR-44); {@code null} for
 *     none. On an edit, the files of the post not named again are deleted
 * @param officialCategory what an announcement is, ANNOUNCEMENT when absent (D-99); a Trader's post has none
 * @param mentions the members the text mentions (D-125), at most 10, each told once; {@code null} for none
 * @param sentiment the author's stance on the market, Bullish or Bearish (D-125); {@code null} for none
 */
public record PostRequest(
        @NotBlank(message = "MSG01") @Size(min = 5, max = 150, message = "MSG01")
        String title,

        @NotBlank(message = "MSG01") @Size(max = 10_000, message = "MSG01")
        String content,

        MarketType marketType,
        @Size(max = 5, message = "MSG01") List<@NotNull(message = "MSG01") String> coins,
        List<@NotNull(message = "MSG01") @Valid MediaRequest> media,
        OfficialCategory officialCategory,
        @Size(max = 10, message = "MSG01") List<@NotNull(message = "MSG01") UUID> mentions,
        PostSentiment sentiment) {

    public PostRequest {
        title = title == null ? null : title.strip();
        content = content == null ? null : content.strip();
        coins = coins == null
                ? List.of()
                : coins.stream()
                        .map(symbol -> symbol == null ? null : symbol.strip().toUpperCase(Locale.ROOT))
                        .distinct()
                        .toList();
        media = media == null ? List.of() : media;
        mentions = mentions == null ? List.of() : mentions.stream().distinct().toList();
    }

    /** A post with these fields that mentions nobody. */
    public PostRequest(
            String title,
            String content,
            MarketType marketType,
            List<String> coins,
            List<MediaRequest> media,
            OfficialCategory officialCategory) {
        this(title, content, marketType, coins, media, officialCategory, List.of(), null);
    }

    /** A post with these fields and no media. */
    public PostRequest(String title, String content, MarketType marketType, List<String> coins) {
        this(title, content, marketType, coins, List.of(), null, List.of(), null);
    }
}
