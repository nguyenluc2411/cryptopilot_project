package com.cryptopilot.community.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.community.dto.request.PostRequest;
import com.cryptopilot.community.dto.response.CoinDiscussionResponse;
import com.cryptopilot.community.dto.response.PostDetailResponse;
import com.cryptopilot.community.dto.response.PostSummaryResponse;
import com.cryptopilot.community.entity.ForumPost;
import com.cryptopilot.community.model.CoinDiscussion;
import com.cryptopilot.community.model.FeedFilter;
import com.cryptopilot.community.model.FeedPage;
import com.cryptopilot.community.model.FeedQuery;
import com.cryptopilot.community.model.enums.EngagementAction;
import com.cryptopilot.community.model.enums.FeedTab;
import com.cryptopilot.community.model.enums.OfficialCategory;
import com.cryptopilot.community.model.enums.PostStatus;
import com.cryptopilot.community.repository.EngagementRepository;
import com.cryptopilot.community.repository.ForumPostRepository;
import com.cryptopilot.community.repository.PostFeedRepository;
import com.cryptopilot.community.service.PostService;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The community use cases of a Trader.
 *
 * <p>Visibility follows BR-47 and D-125 everywhere: a post is read through {@link CommunityAccess} and changed only
 * through a lookup by its key <em>and</em> its author, so another Trader's post, a hidden post of someone else and a
 * deleted post all answer MSG41 — never "forbidden", which would confirm that the key exists.
 *
 * <p>Posts and their files are written by {@link PostWriter}, which checks the files against the media storage
 * before its transaction opens.
 *
 * <p>The {@code MOST_LIKED} tab ranks the posts published in the last seven days by their likes (SRS 3.7.1).
 *
 * <p>Rule: UC-26, UC-27, UC-29; BR-47, BR-61; SRS 3.7.1, 3.7.2; CR-04.
 *
 * <p>Reference: OWASP Foundation (2023). <i>API Security Top 10</i>, API1:2023 "Broken Object Level Authorization".
 */
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    /** How far back the {@code MOST_LIKED} tab looks (SRS 3.7.1). */
    static final Duration MOST_LIKED_WINDOW = Duration.ofDays(7);

    /** The bounds of a search text (D-99). */
    static final int SEARCH_MIN_LENGTH = 2;

    static final int SEARCH_MAX_LENGTH = 100;

    /** How many coins the Trending list shows, and at most (D-99). */
    static final int TRENDING_COINS_DEFAULT = 10;

    static final int TRENDING_COINS_MAX = 20;

    private final ForumPostRepository posts;
    private final PostFeedRepository feeds;
    private final PostAssembler assembler;
    private final PostWriter writer;
    private final EngagementRepository engagements;
    private final CommunityAccess access;
    private final MarketApi market;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PostSummaryResponse> feed(
            UUID caller, FeedTab tab, FeedFilter filter, Integer page, Integer pageSize) {
        if (caller == null && (tab == FeedTab.SAVED || tab == FeedTab.MINE || tab == FeedTab.FOLLOWING)) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "the " + tab + " tab is a Trader's own");
        }
        PageRequest request = Paging.of(page, pageSize);
        String text = blankToNull(filter.text());
        if (text != null && (text.length() < SEARCH_MIN_LENGTH || text.length() > SEARCH_MAX_LENGTH)) {
            throw new FieldValidationException(
                    "a search has " + SEARCH_MIN_LENGTH + " to " + SEARCH_MAX_LENGTH + " characters",
                    Map.of("q", "MSG15"));
        }
        UUID coinId = null;
        if (blankToNull(filter.coin()) != null) {
            Optional<UUID> stored = coinId(filter.coin());
            if (stored.isEmpty()) {
                // No post can be tagged with a coin that is not stored.
                return new PageResponse<>(List.of(), page(request), request.getPageSize(), 0);
            }
            coinId = stored.get();
        }
        FeedQuery query = new FeedQuery(tab, coinId, filter.market(), filter.author(), text);
        Instant now = clock.instant();
        FeedPage found = feeds.feed(query, caller, now.minus(MOST_LIKED_WINDOW), now, request);
        return assembler.page(found, request, caller);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PostSummaryResponse> pinned(
            UUID caller, OfficialCategory category, Integer page, Integer pageSize) {
        PageRequest request = Paging.of(page, pageSize);
        return assembler.page(feeds.official(false, category, request), request, caller);
    }

    @Override
    @Transactional(readOnly = true)
    public CoinDiscussionResponse coinDiscussion(String symbol) {
        CoinListing coin = market.coinsBySymbol(List.of(symbol.strip().toUpperCase(Locale.ROOT))).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Coin", symbol));
        CoinDiscussion discussion = feeds.coinDiscussion(coin.coinId(), clock.instant());
        return new CoinDiscussionResponse(
                coin.symbol(),
                coin.name(),
                discussion.postsLastDay(),
                discussion.postsLastWeek(),
                discussion.totalPosts(),
                discussion.lastPostAt());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CoinDiscussionResponse> trendingCoins(Integer limit) {
        int size = limit == null ? TRENDING_COINS_DEFAULT : Math.clamp(limit, 1, TRENDING_COINS_MAX);
        List<PostFeedRepository.TrendingCoin> trending = feeds.trendingCoins(clock.instant(), size);
        Map<UUID, CoinListing> coins = market
                .coins(trending.stream()
                        .map(PostFeedRepository.TrendingCoin::coinId)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(CoinListing::coinId, Function.identity()));
        return trending.stream()
                .filter(coin -> coins.containsKey(coin.coinId()))
                .map(coin -> new CoinDiscussionResponse(
                        coins.get(coin.coinId()).symbol(),
                        coins.get(coin.coinId()).name(),
                        coin.postsLastDay(),
                        coin.postsLastWeek(),
                        coin.totalPosts(),
                        coin.lastPostAt()))
                .toList();
    }

    @Override
    @Transactional
    public PostDetailResponse detail(UUID caller, UUID postId) {
        ForumPost post = posts.findById(postId)
                .filter(found -> access.canSee(found, caller))
                .orElseThrow(() -> notFound(postId));
        // A signed-in member other than the author counts once as a view; a Guest is not counted (D-99).
        if (caller != null && !caller.equals(post.getAuthorId()) && post.getStatus() == PostStatus.PUBLISHED) {
            engagements.addToPost(UuidV7.next(), caller, post.getId(), EngagementAction.VIEW.name(), clock.instant());
        }
        return assembler.detail(post, caller);
    }

    @Override
    public PostDetailResponse create(UUID caller, PostRequest request) {
        return writer.create(caller, request, false);
    }

    @Override
    public PostDetailResponse update(UUID caller, UUID postId, PostRequest request) {
        return writer.update(caller, postId, request, id -> posts.findByIdAndAuthorId(id, caller), true);
    }

    @Override
    public void delete(UUID caller, UUID postId) {
        writer.delete(postId, id -> posts.findByIdAndAuthorId(id, caller));
    }

    private Optional<UUID> coinId(String symbol) {
        return market.coinsBySymbol(List.of(symbol.strip().toUpperCase(Locale.ROOT))).stream()
                .map(CoinListing::coinId)
                .findFirst();
    }

    private static int page(PageRequest request) {
        return request.getPageNumber() + 1;
    }

    private static ResourceNotFoundException notFound(UUID postId) {
        return new ResourceNotFoundException("Post", postId);
    }
}
