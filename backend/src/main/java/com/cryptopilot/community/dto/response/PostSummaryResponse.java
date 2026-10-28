package com.cryptopilot.community.dto.response;

import com.cryptopilot.community.model.enums.OfficialCategory;
import com.cryptopilot.community.model.enums.PostSentiment;
import com.cryptopilot.community.model.enums.PostStatus;
import com.cryptopilot.community.model.enums.PostType;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One post of the community feed (SCR-24): what its card shows, without the full text.
 *
 * <p>Rule: UC-26; SRS 3.7.1.
 *
 * @param id the post
 * @param author who wrote it
 * @param authorStanding how the author counts in the community (D-125)
 * @param title the title
 * @param excerpt the start of the text, at most 300 characters
 * @param postType TEXT or VIDEO
 * @param marketType the market it is about, or {@code null}
 * @param official whether it is an announcement, pinned above the feed (BR-61)
 * @param officialCategory what an official post is; {@code null} for a Trader's post
 * @param status PUBLISHED, or HIDDEN in the author's own tabs (BR-47)
 * @param sentiment the author's stance, Bullish or Bearish, or {@code null} (D-125)
 * @param coins the symbols it is tagged with, alphabetical
 * @param cover its first image or its video, as the card previews it; {@code null} for a post without media
 * @param likeCount its likes
 * @param commentCount its published comments
 * @param viewCount the signed-in members other than the author who opened it, each once (D-125)
 * @param bullishCount the readers who voted it Bullish (D-125)
 * @param bearishCount the readers who voted it Bearish (D-125)
 * @param myVote how the caller voted it, or {@code null}
 * @param likedByMe whether the caller liked it
 * @param savedByMe whether the caller saved it
 * @param createdAt when it was published
 * @param updatedAt when it was last changed
 */
public record PostSummaryResponse(
        UUID id,
        AuthorResponse author,
        StandingResponse authorStanding,
        String title,
        String excerpt,
        PostType postType,
        MarketType marketType,
        boolean official,
        OfficialCategory officialCategory,
        PostStatus status,
        PostSentiment sentiment,
        List<String> coins,
        MediaResponse cover,
        long likeCount,
        long commentCount,
        long viewCount,
        long bullishCount,
        long bearishCount,
        PostSentiment myVote,
        boolean likedByMe,
        boolean savedByMe,
        Instant createdAt,
        Instant updatedAt) {}
