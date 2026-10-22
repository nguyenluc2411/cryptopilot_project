package com.cryptopilot.community.service;

import com.cryptopilot.community.dto.response.MemberProfileResponse;
import java.util.UUID;

/**
 * The members of the community (D-99, CoinMarketCap Community): public pages. A member who does not exist, has no
 * profile or is banned answers MSG41. Implemented by {@link com.cryptopilot.community.service.impl.MemberServiceImpl}.
 *
 * <p>Rule: D-48, D-99.
 */
public interface MemberService {

    /** A member's public page; {@code caller} is {@code null} for a Guest. */
    MemberProfileResponse profile(UUID caller, UUID memberId);
}
