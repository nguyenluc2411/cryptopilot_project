package com.cryptopilot.community.job;

import com.cryptopilot.community.service.MemberService;
import com.cryptopilot.user.event.AccountBanned;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Takes a banned member out of the community (D-125). A ban is final (BR-05), so their published posts and comments
 * are hidden, as moderation hides a post, and their follows and blocks are dropped, so no count or list names a member
 * whose page is gone. What it hides stays hidden: a moderator's restore of a post or comment of a banned author is
 * refused with {@code AUTHOR_BANNED} (MSG43). The event reaches this class through the event publication registry
 * after the ban committed (D-98); the change is the same the second time, so a delivery repeated after a failure is
 * harmless. The ban itself is in the audit log; the content it hides is not audited row by row. Orchestration only:
 * the work is {@link MemberService#removeBanned}.
 *
 * <p>Rule: BR-05, BR-47; NSF-15; D-98, D-125.
 */
@Component
public class BannedMemberListener {

    private final MemberService members;

    public BannedMemberListener(MemberService members) {
        this.members = members;
    }

    @ApplicationModuleListener
    public void on(AccountBanned banned) {
        members.removeBanned(banned.userId(), banned.bannedAt());
    }
}
