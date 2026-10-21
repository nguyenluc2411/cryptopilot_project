package com.cryptopilot.notification.service;

import com.cryptopilot.community.event.PostCommented;
import com.cryptopilot.community.event.PostModerated;
import com.cryptopilot.community.event.UserFollowed;

/**
 * The in-app notifications of the community (E-1): a post's author is told about new comments (SRS 3.7.3), the author
 * and the reporters of a post about a moderator's decision (SRS 3.11.3), and a member about a new follower (D-99).
 * Each event writes its notifications once however often it is delivered. Implemented by
 * {@link com.cryptopilot.notification.service.impl.CommunityNotificationServiceImpl}.
 *
 * <p>Rule: SRS 3.7.3, 3.11.3; UC-30, UC-43; D-99; NSF-15.
 */
public interface CommunityNotificationService {

    /** Tells the post's author about the comment, unless they wrote it. */
    void commented(PostCommented event);

    /** Tells the author what was decided about their post, unless the reports were dismissed, and each reporter. */
    void moderated(PostModerated event);

    /** Tells the member followed, unless an unread notification already says this member follows them. */
    void followed(UserFollowed event);
}
