package com.cryptopilot.community.service.impl;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.community.dto.request.PostRequest;
import com.cryptopilot.community.dto.response.PostDetailResponse;
import com.cryptopilot.community.dto.response.PostSummaryResponse;
import com.cryptopilot.community.entity.ForumPost;
import com.cryptopilot.community.model.FeedPage;
import com.cryptopilot.community.model.FeedQuery;
import com.cryptopilot.community.model.enums.FeedTab;
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
    private final MarketApi market;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PostSummaryResponse> feed(
            UUID caller, FeedTab tab, String coin, MarketType market, Integer page, Integer pageSize) {
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
    public PageResponse<PostSummaryResponse> pinned(UUID caller, Integer page, Integer pageSize) {
        PageRequest request = Paging.of(page, pageSize);
        return assembler.page(feeds.official(false, request), request, caller);
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
    @Transactional
    public PostDetailResponse create(UUID caller, PostRequest request) {
        ForumPost post = ForumPost.publish(
                caller, request.title(), request.content(), request.marketType(), assembler.coinTags(request.coins()));
        posts.save(post);
        posts.flush();
        return assembler.detail(post, caller);
    }

    @Override
    @Transactional
    public PostDetailResponse update(UUID caller, UUID postId, PostRequest request) {
        ForumPost post = owned(caller, postId);
        post.revise(request.title(), request.content(), request.marketType(), assembler.coinTags(request.coins()));
        posts.flush();
        return assembler.detail(post, caller);
    }

    @Override
    @Transactional
    public void delete(UUID caller, UUID postId) {
        owned(caller, postId).delete();
    }

    private ForumPost owned(UUID caller, UUID postId) {
        return posts.findByIdAndAuthorId(postId, caller)
                .filter(post -> !post.isDeleted())
                .orElseThrow(() -> notFound(postId));
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
