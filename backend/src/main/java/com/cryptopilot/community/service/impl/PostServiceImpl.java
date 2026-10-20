package com.cryptopilot.community.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.community.dto.request.PostRequest;
import com.cryptopilot.community.dto.response.CoinDiscussionResponse;
import com.cryptopilot.community.dto.response.PostDetailResponse;
import com.cryptopilot.community.dto.response.PostSummaryResponse;
import com.cryptopilot.community.entity.ForumPost;
import com.cryptopilot.community.model.CoinDiscussion;
import com.cryptopilot.community.model.FeedPage;
import com.cryptopilot.community.model.FeedQuery;
import com.cryptopilot.community.model.enums.FeedTab;
import com.cryptopilot.community.model.enums.OfficialCategory;
import com.cryptopilot.community.repository.ForumPostRepository;
import com.cryptopilot.community.repository.PostFeedRepository;
import com.cryptopilot.community.service.PostService;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The community use cases of a Trader.
 *
 * <p>Visibility follows BR-47 everywhere: a post is read through {@link ForumPost#isVisibleTo(UUID)} and changed only
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

    private final ForumPostRepository posts;
    private final PostFeedRepository feeds;
    private final PostAssembler assembler;
    private final PostWriter writer;
    private final MarketApi market;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PostSummaryResponse> feed(
            UUID caller, FeedTab tab, String coin, MarketType market, Integer page, Integer pageSize) {
        if (caller == null && (tab == FeedTab.SAVED || tab == FeedTab.MINE)) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "the " + tab + " tab is a Trader's own");
        }
        PageRequest request = Paging.of(page, pageSize);
        UUID coinId = null;
        if (coin != null && !coin.isBlank()) {
            Optional<UUID> stored = coinId(coin);
            if (stored.isEmpty()) {
                // No post can be tagged with a coin that is not stored.
                return new PageResponse<>(List.of(), page(request), request.getPageSize(), 0);
            }
            coinId = stored.get();
        }
        FeedPage found = feeds.feed(
                new FeedQuery(tab, coinId, market), caller, clock.instant().minus(MOST_LIKED_WINDOW), request);
        return assembler.page(found, request, caller);
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
    public PostDetailResponse detail(UUID caller, UUID postId) {
        ForumPost post = posts.findById(postId)
                .filter(found -> found.isVisibleTo(caller))
                .orElseThrow(() -> notFound(postId));
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
