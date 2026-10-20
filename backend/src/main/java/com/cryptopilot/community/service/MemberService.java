package com.cryptopilot.community.service;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.community.dto.response.AuthorResponse;
import com.cryptopilot.community.dto.response.EngagementResponse;
import com.cryptopilot.community.dto.response.MemberProfileResponse;
import java.util.UUID;

/**
 * The members of the community (D-99, CoinMarketCap Community): public pages and following. A member who does not
 * exist, has no profile or is banned answers MSG41. Implemented by
 * {@link com.cryptopilot.community.service.impl.MemberServiceImpl}.
 *
 * <p>Rule: D-48, D-99.
 */
public interface MemberService {

    /** A member's public page; {@code caller} is {@code null} for a Guest. */
    MemberProfileResponse profile(UUID caller, UUID memberId);

    /**
     * Follows a member, or stops following, answering whether the caller follows them now and their follower count.
     *
     * @throws com.cryptopilot.common.exception.BusinessException {@code FOLLOW_SELF} for the caller's own account
     */
    EngagementResponse follow(UUID caller, UUID memberId, boolean active);

    /** One page of a member's followers, the most recent first. */
    PageResponse<AuthorResponse> followers(UUID memberId, Integer page, Integer pageSize);

    /** One page of the members a member follows, the most recent first. */
    PageResponse<AuthorResponse> following(UUID memberId, Integer page, Integer pageSize);
}
