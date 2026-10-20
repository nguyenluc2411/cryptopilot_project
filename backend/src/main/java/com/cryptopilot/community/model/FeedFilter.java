package com.cryptopilot.community.model;

import com.cryptopilot.community.model.enums.Sentiment;
import com.cryptopilot.market.model.enums.MarketType;
import java.util.UUID;

/**
 * The filters of the community feed (SCR-24) as the client names them; every one is optional and they combine.
 *
 * <p>Rule: UC-26; SRS 3.7.1; D-99.
 *
 * @param coin only posts tagged with this coin symbol, e.g. {@code BTC}
 * @param market only posts about this market
 * @param sentiment only posts labelled so (D-99)
 * @param author only posts of this member (D-99)
 * @param hashtag only posts with this hashtag, with or without its {@code #} (D-99)
 * @param text only posts whose title or text contains this, 2 to 100 characters, case ignored (D-99)
 */
public record FeedFilter(
        String coin, MarketType market, Sentiment sentiment, UUID author, String hashtag, String text) {

    /** No filter. */
    public static final FeedFilter NONE = new FeedFilter(null, null, null, null, null, null);
}
