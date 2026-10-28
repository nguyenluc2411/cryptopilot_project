package com.cryptopilot.community.service;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.community.dto.response.AuthorResponse;
import com.cryptopilot.community.dto.response.EngagementResponse;
import com.cryptopilot.community.dto.response.InfluencerResponse;
import com.cryptopilot.community.dto.response.MemberProfileResponse;
import com.cryptopilot.community.model.enums.InfluencerRank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The members of the community (D-99, D-125, CoinMarketCap Community): public pages, following and blocking. A
 * member who does not exist, has no profile or is banned answers MSG41. Implemented by
 * {@link com.cryptopilot.community.service.impl.MemberServiceImpl}.
 *
 * <p>Rule: D-48, D-99, D-125.
 */
public interface MemberService {

    /**
     * A member's public page; {@code caller} is {@code null} for a Guest. MSG41 when the member blocks the caller (a
     * block works both ways, D-125); the caller's own block leaves the page open, so it can be lifted there.
     */
    MemberProfileResponse profile(UUID caller, UUID memberId);

    /**
     * Follows a member, answering that the caller follows them now and their follower count.
     *
     * @throws com.cryptopilot.common.exception.BusinessException {@code FOLLOW_SELF} for the caller's own account,
     *     MSG41 when the member blocks the caller, {@code RELATION_NOT_ALLOWED} for an administrator or a member the
     *     caller blocks
     */
    EngagementResponse follow(UUID caller, UUID memberId);

    /** Stops following a member, answering that the caller does not follow them and their follower count. */
    EngagementResponse unfollow(UUID caller, UUID memberId);

    /**
     * Blocks a member: the caller stops following them, they stop following the caller, and their posts and comments
     * are no longer shown to the caller. Answers that the caller blocks them and how many members the caller blocks.
     *
     * @throws com.cryptopilot.common.exception.BusinessException {@code BLOCK_SELF} for the caller's own account,
     *     {@code RELATION_NOT_ALLOWED} for an administrator
     */
    EngagementResponse block(UUID caller, UUID memberId);

    /**
     * Stops blocking a member, answering that the caller does not block them and how many members the caller blocks.
     */
    EngagementResponse unblock(UUID caller, UUID memberId);

    /**
     * One page of a member's followers, the most recent first, without the members in a block with the caller;
     * {@code caller} is {@code null} for a Guest. MSG41 when the member blocks the caller.
     */
    PageResponse<AuthorResponse> followers(UUID caller, UUID memberId, Integer page, Integer pageSize);

    /**
     * One page of the members a member follows, the most recent first, without the members in a block with the
     * caller; {@code caller} is {@code null} for a Guest. MSG41 when the member blocks the caller.
     */
    PageResponse<AuthorResponse> following(UUID caller, UUID memberId, Integer page, Integer pageSize);

    /** One page of the members the caller blocks, the most recent first; only the caller sees it. */
    PageResponse<AuthorResponse> blocked(UUID caller, Integer page, Integer pageSize);

    /**
     * The Traders whose name starts with {@code prefix}, to mention in a post or a comment (D-125): at most ten,
     * alphabetical, without the caller and the members either of the two blocks.
     *
     * @throws com.cryptopilot.common.exception.FieldValidationException MSG15 when {@code prefix} is not 1 to 50
     *     characters
     */
    List<AuthorResponse> search(UUID caller, String prefix);

    /**
     * The members who count most in the community (D-125), by influence or analytical depth, INFLUENCE when
     * {@code null}: at most {@code limit}, 1 to 20, 10 when {@code null}. Members in a block with the caller are left
     * out; a Guest is {@code null}.
     */
    List<InfluencerResponse> influencers(UUID caller, InfluencerRank rank, Integer limit);

    /**
     * Takes a banned member out of the community (D-125): hides their published posts and comments and drops their
     * follows and blocks. Changes nothing the second time.
     */
    void removeBanned(UUID memberId, Instant bannedAt);
}
