package com.cryptopilot.community.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.community.dto.response.AuthorResponse;
import com.cryptopilot.community.dto.response.EngagementResponse;
import com.cryptopilot.community.dto.response.InfluencerResponse;
import com.cryptopilot.community.dto.response.MemberProfileResponse;
import com.cryptopilot.community.entity.UserRelation;
import com.cryptopilot.community.event.UserFollowed;
import com.cryptopilot.community.model.AuthorStanding;
import com.cryptopilot.community.model.MemberActivity;
import com.cryptopilot.community.model.enums.InfluencerRank;
import com.cryptopilot.community.model.enums.RelationType;
import com.cryptopilot.community.repository.ForumCommentRepository;
import com.cryptopilot.community.repository.ForumPostRepository;
import com.cryptopilot.community.repository.PostFeedRepository;
import com.cryptopilot.community.repository.UserRelationRepository;
import com.cryptopilot.community.service.MemberService;
import com.cryptopilot.user.AuthorCard;
import com.cryptopilot.user.PublicProfile;
import com.cryptopilot.user.UserApi;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member pages, following and blocking (D-99, D-125). Each change is one idempotent statement, so a repeated or
 * concurrent click keeps one row; a new follow publishes {@link UserFollowed} for the notification of the member
 * followed. A banned member is gone from the community: no page, no new followers. Administrators speak through
 * official posts, which the Following tab leaves out, so they are neither followed nor blocked.
 *
 * <p>Rule: D-99, D-125.
 */
@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    /** Stands for a Guest in the lists that leave out members in a block with the viewer: no account has this key. */
    private static final UUID NOBODY = new UUID(0L, 0L);

    /** Most members a mention search answers. */
    static final int SEARCH_LIMIT = 10;

    /** How many members Top Influencers lists, and at most (D-125). */
    static final int INFLUENCERS_DEFAULT = 10;

    static final int INFLUENCERS_MAX = 20;

    /** Longest name prefix a mention search takes, the length of a display name. */
    static final int SEARCH_MAX_LENGTH = 50;

    /** The scope of the lock a follow and a block take for a pair of members, keyed by the lower of the two ids. */
    static final String PAIR_SCOPE = "community-relation";

    private final UserApi users;
    private final UserLock lock;
    private final UserRelationRepository relations;
    private final PostFeedRepository feeds;
    private final ForumPostRepository posts;
    private final ForumCommentRepository comments;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public MemberProfileResponse profile(UUID caller, UUID memberId) {
        PublicProfile member = visibleMember(caller, memberId);
        MemberActivity activity = feeds.memberActivity(memberId);
        return new MemberProfileResponse(
                member.userId(),
                member.displayName(),
                member.avatarUrl(),
                member.joinedAt(),
                activity.posts(),
                activity.likesReceived(),
                relations.countByTargetUserIdAndType(memberId, RelationType.FOLLOW),
                relations.countBySourceUserIdAndType(memberId, RelationType.FOLLOW),
                caller != null
                        && relations.existsBySourceUserIdAndTargetUserIdAndType(caller, memberId, RelationType.FOLLOW),
                caller != null
                        && relations.existsBySourceUserIdAndTargetUserIdAndType(caller, memberId, RelationType.BLOCK),
                memberId.equals(caller));
    }

    @Override
    @Transactional
    public EngagementResponse follow(UUID caller, UUID memberId) {
        if (memberId.equals(caller)) {
            throw new BusinessException(ErrorCode.FOLLOW_SELF, "a member cannot follow themselves");
        }
        visibleMember(caller, memberId);
        requireTrader(memberId, "followed");
        lockPair(caller, memberId);
        // Checked again under the lock: the member may have blocked the caller meanwhile.
        visibleMember(caller, memberId);
        if (relations.existsBySourceUserIdAndTargetUserIdAndType(caller, memberId, RelationType.BLOCK)) {
            throw new BusinessException(
                    ErrorCode.RELATION_NOT_ALLOWED, "member " + caller + " blocks " + memberId + "; unblock first");
        }
        Instant now = clock.instant();
        if (relations.follow(UuidV7.next(), caller, memberId, now) == 1) {
            events.publishEvent(new UserFollowed(caller, memberId, now));
        }
        return new EngagementResponse(true, relations.countByTargetUserIdAndType(memberId, RelationType.FOLLOW));
    }

    @Override
    @Transactional
    public EngagementResponse unfollow(UUID caller, UUID memberId) {
        relations.remove(caller, memberId, RelationType.FOLLOW.name());
        return new EngagementResponse(false, relations.countByTargetUserIdAndType(memberId, RelationType.FOLLOW));
    }

    @Override
    @Transactional
    public EngagementResponse block(UUID caller, UUID memberId) {
        if (memberId.equals(caller)) {
            throw new BusinessException(ErrorCode.BLOCK_SELF, "a member cannot block themselves");
        }
        member(memberId);
        requireTrader(memberId, "blocked");
        lockPair(caller, memberId);
        relations.block(UuidV7.next(), caller, memberId, clock.instant());
        relations.remove(memberId, caller, RelationType.FOLLOW.name());
        return new EngagementResponse(true, relations.countBySourceUserIdAndType(caller, RelationType.BLOCK));
    }

    @Override
    @Transactional
    public EngagementResponse unblock(UUID caller, UUID memberId) {
        relations.remove(caller, memberId, RelationType.BLOCK.name());
        return new EngagementResponse(false, relations.countBySourceUserIdAndType(caller, RelationType.BLOCK));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuthorResponse> followers(UUID caller, UUID memberId, Integer page, Integer pageSize) {
        visibleMember(caller, memberId);
        return cards(
                relations.findFollowersSeenBy(memberId, viewer(caller), Paging.of(page, pageSize)),
                UserRelation::getSourceUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuthorResponse> following(UUID caller, UUID memberId, Integer page, Integer pageSize) {
        visibleMember(caller, memberId);
        return cards(
                relations.findFollowingSeenBy(memberId, viewer(caller), Paging.of(page, pageSize)),
                UserRelation::getTargetUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuthorResponse> blocked(UUID caller, Integer page, Integer pageSize) {
        return cards(
                relations.findBySourceUserIdAndTypeOrderByCreatedAtDescIdDesc(
                        caller, RelationType.BLOCK, Paging.of(page, pageSize)),
                UserRelation::getTargetUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuthorResponse> search(UUID caller, String prefix) {
        String text = prefix == null ? "" : prefix.strip();
        if (text.isEmpty() || text.length() > SEARCH_MAX_LENGTH) {
            throw new FieldValidationException(
                    "a member search has 1 to " + SEARCH_MAX_LENGTH + " characters", Map.of("q", "MSG15"));
        }
        // One more than shown for the caller, who is left out, and the rest filtered after the blocks are read.
        List<AuthorCard> found = users.searchMembers(text, SEARCH_LIMIT * 2 + 1);
        Set<UUID> hidden = found.isEmpty()
                ? new HashSet<>()
                : new HashSet<>(relations.blockedEitherWay(
                        caller, found.stream().map(AuthorCard::userId).toList()));
        hidden.add(caller);
        return found.stream()
                .filter(card -> !hidden.contains(card.userId()))
                .limit(SEARCH_LIMIT)
                .map(card -> new AuthorResponse(card.userId(), card.displayName(), card.avatarUrl()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<InfluencerResponse> influencers(UUID caller, InfluencerRank rank, Integer limit) {
        int size = limit == null ? INFLUENCERS_DEFAULT : Math.clamp(limit, 1, INFLUENCERS_MAX);
        List<PostFeedRepository.Ranked> ranked =
                feeds.influencers(rank == null ? InfluencerRank.INFLUENCE : rank, caller, clock.instant(), size);
        Map<UUID, AuthorCard> cards =
                users
                        .authors(ranked.stream()
                                .map(PostFeedRepository.Ranked::memberId)
                                .collect(Collectors.toSet()))
                        .stream()
                        .collect(Collectors.toMap(AuthorCard::userId, Function.identity()));
        return ranked.stream()
                .map(row -> {
                    AuthorCard card = cards.get(row.memberId());
                    AuthorStanding standing = row.standing();
                    return new InfluencerResponse(
                            row.memberId(),
                            card == null ? null : card.displayName(),
                            card == null ? null : card.avatarUrl(),
                            standing.influence(),
                            standing.depth(),
                            standing.followers(),
                            standing.bullishPosts(),
                            standing.bearishPosts(),
                            standing.followedByCaller(),
                            row.memberId().equals(caller));
                })
                .toList();
    }

    @Override
    @Transactional
    public void removeBanned(UUID memberId, Instant bannedAt) {
        posts.hideAllOf(memberId, bannedAt);
        comments.hideAllOf(memberId, bannedAt);
        relations.removeAllOf(memberId);
    }

    private PageResponse<AuthorResponse> cards(Page<UserRelation> rows, Function<UserRelation, UUID> who) {
        Map<UUID, AuthorCard> authors = users.authors(rows.map(who).toSet()).stream()
                .collect(Collectors.toMap(AuthorCard::userId, Function.identity()));
        return PageResponse.of(rows, row -> {
            UUID id = who.apply(row);
            AuthorCard card = authors.get(id);
            return new AuthorResponse(
                    id, card == null ? null : card.displayName(), card == null ? null : card.avatarUrl());
        });
    }

    // A follow checks that no block stands between the two and then inserts, while a block removes the follow back:
    // without one lock per pair, a follow back committed beside a block would survive it. The key is a name-based
    // UUID of the two keys in order, so it is the same from either side and a member's other pairs do not wait.
    private void lockPair(UUID one, UUID other) {
        String pair = one.compareTo(other) < 0 ? one + "/" + other : other + "/" + one;
        lock.lock(PAIR_SCOPE, UUID.nameUUIDFromBytes(pair.getBytes(StandardCharsets.UTF_8)));
    }

    private void requireTrader(UUID memberId, String what) {
        if (users.isAdministrator(memberId)) {
            throw new BusinessException(
                    ErrorCode.RELATION_NOT_ALLOWED, "an administrator " + memberId + " is not " + what);
        }
    }

    // A member who blocks the caller is gone for the caller, as if banned: the block is not revealed (D-125).
    private PublicProfile visibleMember(UUID caller, UUID memberId) {
        PublicProfile member = member(memberId);
        if (caller != null
                && !caller.equals(memberId)
                && relations.existsBySourceUserIdAndTargetUserIdAndType(memberId, caller, RelationType.BLOCK)) {
            throw new ResourceNotFoundException("Member", memberId);
        }
        return member;
    }

    private static UUID viewer(UUID caller) {
        return caller == null ? NOBODY : caller;
    }

    private PublicProfile member(UUID memberId) {
        return users.publicProfile(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
    }
}
