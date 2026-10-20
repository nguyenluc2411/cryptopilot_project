package com.cryptopilot.community.service.impl;

import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.community.dto.response.AuthorResponse;
import com.cryptopilot.community.dto.response.MediaResponse;
import com.cryptopilot.community.dto.response.PostDetailResponse;
import com.cryptopilot.community.dto.response.PostSummaryResponse;
import com.cryptopilot.community.entity.ForumPost;
import com.cryptopilot.community.entity.PostMedia;
import com.cryptopilot.community.model.FeedPage;
import com.cryptopilot.community.model.PostEngagement;
import com.cryptopilot.community.repository.ForumPostRepository;
import com.cryptopilot.community.repository.PostFeedRepository;
import com.cryptopilot.community.repository.PostMediaRepository;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.user.AuthorCard;
import com.cryptopilot.user.UserApi;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * Turns posts into what the community screens show, reading what belongs to other modules — the author's name and
 * avatar, the coin symbols — and the counts once per page rather than once per post, and checks the coin tags a form
 * names against the listed coins.
 *
 * <p>Rule: UC-26, UC-27; SRS 3.7.1, 3.7.2; BR-07.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PostAssembler {

    /** Longest excerpt of a feed card, in characters. */
    static final int EXCERPT_LENGTH = 300;

    private final ForumPostRepository posts;
    private final PostFeedRepository feeds;
    private final PostMediaRepository media;
    private final UserApi users;
    private final MarketApi market;

    /**
     * The coin keys of the symbols a form names, each a coin a listed pair trades; a symbol that is not is refused
     * under {@code coins} (MSG01) and nothing is stored.
     */
    Set<UUID> coinTags(List<String> symbols) {
        if (symbols.isEmpty()) {
            return Set.of();
        }
        Map<String, UUID> listed = market.listedCoinsBySymbol(symbols).stream()
                .collect(Collectors.toMap(CoinListing::symbol, CoinListing::coinId));
        if (!listed.keySet().containsAll(symbols)) {
            Set<String> refused = new HashSet<>(symbols);
            refused.removeAll(listed.keySet());
            throw new FieldValidationException("no listed pair trades the coins " + refused, Map.of("coins", "MSG01"));
        }
        return Set.copyOf(listed.values());
    }

    /** The feed cards of one page, in the page's order. */
    PageResponse<PostSummaryResponse> page(FeedPage page, Pageable request, UUID caller) {
        Map<UUID, ForumPost> rows = posts.findAllByIdIn(page.postIds()).stream()
                .collect(Collectors.toMap(ForumPost::getId, Function.identity()));
        List<ForumPost> ordered = page.postIds().stream()
                .map(rows::get)
                .filter(post -> post != null)
                .toList();
        Map<UUID, Set<UUID>> tags = feeds.coinTags(page.postIds());
        Map<UUID, List<String>> topics = feeds.hashtags(page.postIds());
        Map<UUID, String> symbols =
                symbols(tags.values().stream().flatMap(Set::stream).toList());
        Map<UUID, AuthorCard> authors =
                authors(ordered.stream().map(ForumPost::getAuthorId).toList());
        Map<UUID, PostEngagement> engagement = feeds.engagement(page.postIds(), caller);
        Map<UUID, MediaResponse> covers = new HashMap<>();
        for (PostMedia file : media.findAllByPostIdInOrderBySortOrderAsc(page.postIds())) {
            covers.putIfAbsent(file.getPostId(), media(file));
        }
        List<PostSummaryResponse> items = ordered.stream()
                .map(post -> summary(
                        post,
                        authors.get(post.getAuthorId()),
                        coins(tags.getOrDefault(post.getId(), Set.of()), symbols),
                        topics.getOrDefault(post.getId(), List.of()),
                        covers.get(post.getId()),
                        engagement.getOrDefault(post.getId(), PostEngagement.NONE)))
                .toList();
        return new PageResponse<>(items, request.getPageNumber() + 1, request.getPageSize(), page.total());
    }

    /** SCR-25 for one post the caller may see. */
    PostDetailResponse detail(ForumPost post, UUID caller) {
        Map<UUID, String> symbols = symbols(post.getCoinIds());
        PostEngagement counts =
                feeds.engagement(List.of(post.getId()), caller).getOrDefault(post.getId(), PostEngagement.NONE);
        return new PostDetailResponse(
                post.getId(),
                author(post.getAuthorId(), authors(List.of(post.getAuthorId())).get(post.getAuthorId())),
                post.getTitle(),
                post.getContent(),
                post.getPostType(),
                post.getMarketType(),
                post.isOfficial(),
                post.getOfficialCategory(),
                post.getStatus(),
                post.getSentiment(),
                coins(post.getCoinIds(), symbols),
                post.getHashtags().stream().sorted().toList(),
                media.findAllByPostIdOrderBySortOrderAsc(post.getId()).stream()
                        .map(PostAssembler::media)
                        .toList(),
                counts.likes(),
                counts.comments(),
                counts.likedByCaller(),
                counts.savedByCaller(),
                post.getAuthorId().equals(caller),
                post.getCreatedAt(),
                post.getUpdatedAt());
    }

    private PostSummaryResponse summary(
            ForumPost post,
            AuthorCard author,
            List<String> coins,
            List<String> hashtags,
            MediaResponse cover,
            PostEngagement counts) {
        return new PostSummaryResponse(
                post.getId(),
                author(post.getAuthorId(), author),
                post.getTitle(),
                excerpt(post.getContent()),
                post.getPostType(),
                post.getMarketType(),
                post.isOfficial(),
                post.getOfficialCategory(),
                post.getStatus(),
                post.getSentiment(),
                coins,
                hashtags,
                cover,
                counts.likes(),
                counts.comments(),
                counts.likedByCaller(),
                counts.savedByCaller(),
                post.getCreatedAt(),
                post.getUpdatedAt());
    }

    private Map<UUID, AuthorCard> authors(Collection<UUID> userIds) {
        return users.authors(Set.copyOf(userIds)).stream()
                .collect(Collectors.toMap(AuthorCard::userId, Function.identity()));
    }

    private Map<UUID, String> symbols(Collection<UUID> coinIds) {
        return market.coins(Set.copyOf(coinIds)).stream()
                .collect(Collectors.toMap(CoinListing::coinId, CoinListing::symbol));
    }

    private static MediaResponse media(PostMedia file) {
        return new MediaResponse(
                file.getStoragePublicId(),
                file.getMediaType(),
                file.getMediaUrl(),
                file.getThumbnailUrl(),
                file.getWidth(),
                file.getHeight(),
                file.getDurationSeconds());
    }

    private static AuthorResponse author(UUID userId, AuthorCard card) {
        return card == null
                ? new AuthorResponse(userId, null, null)
                : new AuthorResponse(userId, card.displayName(), card.avatarUrl());
    }

    // fk_post_coin_coin keeps every tagged coin stored, so a missing symbol is left out rather than failing the page.
    private static List<String> coins(Set<UUID> coinIds, Map<UUID, String> symbols) {
        return coinIds.stream()
                .map(symbols::get)
                .filter(symbol -> symbol != null)
                .sorted()
                .toList();
    }

    static String excerpt(String content) {
        if (content.length() <= EXCERPT_LENGTH) {
            return content;
        }
        int end = EXCERPT_LENGTH;
        if (Character.isHighSurrogate(content.charAt(end - 1))) {
            end--;
        }
        return content.substring(0, end);
    }
}
