package com.cryptopilot.community.dto.response;

import com.cryptopilot.community.model.enums.OfficialCategory;
import com.cryptopilot.community.model.enums.PostStatus;
import com.cryptopilot.community.model.enums.PostType;
import com.cryptopilot.community.model.enums.Sentiment;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A post as SCR-25 shows it: the card of the feed with the full text.
 *
 * <p>Rule: UC-26; SRS 3.7.1.
 *
 * @param id the post
 * @param author who wrote it
 * @param title the title
 * @param content the full text
 * @param postType TEXT or VIDEO
 * @param marketType the market it is about, or {@code null}
 * @param official whether it is an announcement (BR-61)
 * @param officialCategory what an official post is; {@code null} for a Trader's post
 * @param status PUBLISHED, or HIDDEN when the caller is its author (BR-47)
 * @param sentiment BULLISH or BEARISH as the author labelled it, or {@code null} (D-99)
 * @param coins the symbols it is tagged with, alphabetical
 * @param hashtags its hashtags without the {@code #}, alphabetical (D-99)
 * @param media its images or its video, in order
 * @param likeCount its likes
 * @param commentCount its published comments
 * @param likedByMe whether the caller liked it
 * @param savedByMe whether the caller saved it
 * @param mine whether the caller wrote it, so the client offers Edit and Delete
 * @param createdAt when it was published
 * @param updatedAt when it was last changed
 */
public record PostDetailResponse(
        UUID id,
        AuthorResponse author,
        String title,
        String content,
        PostType postType,
        MarketType marketType,
        boolean official,
        OfficialCategory officialCategory,
        PostStatus status,
        Sentiment sentiment,
        List<String> coins,
        List<String> hashtags,
        List<MediaResponse> media,
        long likeCount,
        long commentCount,
        boolean likedByMe,
        boolean savedByMe,
        boolean mine,
        Instant createdAt,
        Instant updatedAt) {}
