package com.cryptopilot.community.service.impl;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.community.dto.response.MemberProfileResponse;
import com.cryptopilot.community.model.MemberActivity;
import com.cryptopilot.community.repository.PostFeedRepository;
import com.cryptopilot.community.service.MemberService;
import com.cryptopilot.user.PublicProfile;
import com.cryptopilot.user.UserApi;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member pages (D-99). A banned member is gone from the community: no page.
 *
 * <p>Rule: D-99.
 */
@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    private final UserApi users;
    private final PostFeedRepository feeds;

    @Override
    @Transactional(readOnly = true)
    public MemberProfileResponse profile(UUID caller, UUID memberId) {
        PublicProfile member =
                users.publicProfile(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        MemberActivity activity = feeds.memberActivity(memberId);
        return new MemberProfileResponse(
                member.userId(),
                member.displayName(),
                member.avatarUrl(),
                member.joinedAt(),
                activity.posts(),
                activity.likesReceived(),
                memberId.equals(caller));
    }
}
