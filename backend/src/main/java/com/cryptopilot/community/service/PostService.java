package com.cryptopilot.community.service;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.community.dto.request.PostRequest;
import com.cryptopilot.community.dto.response.CoinDiscussionResponse;
import com.cryptopilot.community.dto.response.PostDetailResponse;
import com.cryptopilot.community.dto.response.PostSummaryResponse;
import com.cryptopilot.community.dto.response.TrendingResponse;
import com.cryptopilot.community.model.FeedFilter;
import com.cryptopilot.community.model.enums.FeedTab;
import com.cryptopilot.community.model.enums.OfficialCategory;
import com.cryptopilot.community.model.enums.TrendingWindow;
import java.util.UUID;

/**
 * The Trader's side of the community (UC-26, UC-27, UC-29). A post the caller may not see answers as if it did not
 * exist (MSG41), and so does another Trader's post the caller tries to change. Implemented by
 * {@link com.cryptopilot.community.service.impl.PostServiceImpl}.
 *
 * <p>The reads take a {@code null} caller for a Guest, who sees what every Trader sees (D-99); the SAVED, MINE and
 * FOLLOWING tabs are a Trader's own and answer MSG44 to a Guest.
 *
 * <p>Rule: UC-26, UC-27, UC-29; BR-47, BR-61; D-48, D-99.
 */
public interface PostService {

    /**
     * One page of a feed tab, filtered.
     *
     * @param page from 1, or {@code null} for 1
     * @param pageSize 1 to 100, or {@code null} for 20
     */
    PageResponse<PostSummaryResponse> feed(UUID caller, FeedTab tab, FeedFilter filter, Integer page, Integer pageSize);

    /** The hashtags and coins of the most published posts of a window (D-99); public. */
    TrendingResponse trending(TrendingWindow window);

    /** One page of the published announcements, newest first (BR-61), of one category or all (D-99). */
    PageResponse<PostSummaryResponse> pinned(UUID caller, OfficialCategory category, Integer page, Integer pageSize);

    /** How much the community talks about a coin (D-99); MSG41 for a coin that is not stored. */
    CoinDiscussionResponse coinDiscussion(String symbol);

    /** A post the caller may see (BR-47); MSG41 otherwise. */
    PostDetailResponse detail(UUID caller, UUID postId);

    /** Publishes a post of the caller (UC-27); the client shows MSG33. */
    PostDetailResponse create(UUID caller, PostRequest request);

    /** Replaces the content of one of the caller's posts that is not deleted (UC-29). */
    PostDetailResponse update(UUID caller, UUID postId, PostRequest request);

    /** Deletes one of the caller's posts (UC-29, BR-47). */
    void delete(UUID caller, UUID postId);
}
