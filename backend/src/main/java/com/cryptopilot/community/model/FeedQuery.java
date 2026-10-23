package com.cryptopilot.community.model;

import com.cryptopilot.community.model.enums.FeedTab;
import com.cryptopilot.market.model.enums.MarketType;
import java.util.Objects;
import java.util.UUID;

/**
 * What a feed is asked for: a tab, and the filters of SCR-24.
 *
 * @param tab the tab
 * @param coinId only posts tagged with this coin, or {@code null} for any
 * @param market only posts about this market, or {@code null} for any
 * @param authorId only posts of this member, or {@code null} for anyone's (D-99)
 * @param text only posts whose title or text contains this, case ignored, or {@code null} (D-99)
 */
public record FeedQuery(FeedTab tab, UUID coinId, MarketType market, UUID authorId, String text) {

    public FeedQuery {
        Objects.requireNonNull(tab, "tab");
    }
}
