package com.cryptopilot.community.service.impl;

import com.cryptopilot.community.event.MembersMentioned;
import com.cryptopilot.community.repository.UserRelationRepository;
import com.cryptopilot.user.UserApi;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The mentions of a post or a comment (D-125). The client picks the members from the mention search and sends their
 * keys; the text only shows their names. A key that does not stand for a Trader the author may reach is left out
 * rather than refused, because the member may have been banned or have blocked the author since the client listed
 * them: unknown and banned accounts, administrators, the author, and members either of the two blocks.
 *
 * <p>Rule: D-125.
 */
@Component
@RequiredArgsConstructor
class MentionResolver {

    private final UserApi users;
    private final UserRelationRepository relations;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** Publishes {@link MembersMentioned} for the members of {@code mentions} the author may reach, if any. */
    void mention(UUID postId, UUID commentId, UUID authorId, List<UUID> mentions) {
        if (mentions.isEmpty()) {
            return;
        }
        Set<UUID> candidates = new HashSet<>(mentions);
        candidates.remove(authorId);
        candidates.removeIf(member -> users.publicProfile(member).isEmpty() || users.isAdministrator(member));
        if (!candidates.isEmpty()) {
            relations.blockedEitherWay(authorId, candidates).forEach(candidates::remove);
        }
        List<UUID> reached =
                mentions.stream().filter(candidates::contains).distinct().toList();
        if (!reached.isEmpty()) {
            events.publishEvent(new MembersMentioned(postId, commentId, authorId, reached, clock.instant()));
        }
    }
}
