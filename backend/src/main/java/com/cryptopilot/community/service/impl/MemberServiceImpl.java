package com.cryptopilot.community.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.community.dto.response.AuthorResponse;
import com.cryptopilot.community.dto.response.EngagementResponse;
import com.cryptopilot.community.dto.response.MemberProfileResponse;
import com.cryptopilot.community.entity.UserFollow;
import com.cryptopilot.community.event.UserFollowed;
import com.cryptopilot.community.model.MemberActivity;
import com.cryptopilot.community.repository.PostFeedRepository;
import com.cryptopilot.community.repository.UserFollowRepository;
import com.cryptopilot.community.service.MemberService;
import com.cryptopilot.user.AuthorCard;
import com.cryptopilot.user.PublicProfile;
import com.cryptopilot.user.UserApi;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member pages and following (D-99). Following is one idempotent statement, so a repeated or concurrent click keeps
 * one row; a new follow publishes {@link UserFollowed} for the notification of the member followed. A banned member is
 * gone from the community: no page, and no new followers.
 *
 * <p>Rule: D-99.
 */
@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    private final UserApi users;
    private final UserFollowRepository follows;
    private final PostFeedRepository feeds;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public MemberProfileResponse profile(UUID caller, UUID memberId) {
        PublicProfile member = member(memberId);
        MemberActivity activity = feeds.memberActivity(memberId);
        return new MemberProfileResponse(
                member.userId(),
                member.displayName(),
                member.avatarUrl(),
                member.joinedAt(),
                activity.posts(),
                activity.likesReceived(),
                follows.countByFolloweeId(memberId),
                follows.countByFollowerId(memberId),
                caller != null && follows.existsByFollowerIdAndFolloweeId(caller, memberId),
                memberId.equals(caller));
    }

    @Override
    @Transactional
    public EngagementResponse follow(UUID caller, UUID memberId, boolean active) {
        if (memberId.equals(caller)) {
            throw new BusinessException(ErrorCode.FOLLOW_SELF, "a member cannot follow themselves");
        }
        if (active) {
            member(memberId);
            Instant now = clock.instant();
            if (follows.follow(UuidV7.next(), caller, memberId, now) == 1) {
                events.publishEvent(new UserFollowed(caller, memberId, now));
            }
        } else {
            follows.unfollow(caller, memberId);
        }
        return new EngagementResponse(active, follows.countByFolloweeId(memberId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuthorResponse> followers(UUID memberId, Integer page, Integer pageSize) {
        member(memberId);
        return cards(
                follows.findByFolloweeIdOrderByCreatedAtDescIdDesc(memberId, Paging.of(page, pageSize)),
                UserFollow::getFollowerId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuthorResponse> following(UUID memberId, Integer page, Integer pageSize) {
        member(memberId);
        return cards(
                follows.findByFollowerIdOrderByCreatedAtDescIdDesc(memberId, Paging.of(page, pageSize)),
                UserFollow::getFolloweeId);
    }

    private PageResponse<AuthorResponse> cards(Page<UserFollow> rows, Function<UserFollow, UUID> who) {
        Map<UUID, AuthorCard> authors = users.authors(rows.map(who).toSet()).stream()
                .collect(Collectors.toMap(AuthorCard::userId, Function.identity()));
        return PageResponse.of(rows, row -> {
            UUID id = who.apply(row);
            AuthorCard card = authors.get(id);
            return new AuthorResponse(
                    id, card == null ? null : card.displayName(), card == null ? null : card.avatarUrl());
        });
    }

    private PublicProfile member(UUID memberId) {
        return users.publicProfile(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
    }
}
