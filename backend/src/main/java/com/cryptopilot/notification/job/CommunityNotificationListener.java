package com.cryptopilot.notification.job;

import com.cryptopilot.community.event.PostCommented;
import com.cryptopilot.community.event.PostModerated;
import com.cryptopilot.community.event.UserFollowed;
import com.cryptopilot.notification.service.CommunityNotificationService;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Turns the community's events into in-app notifications (E-1). Each event reaches this class through the event
 * publication registry: recorded in the transaction that stored the comment, the decision or the follow, delivered
 * asynchronously in a transaction of its own after that one commits (D-98). A failure propagates, so the outbox
 * processor delivers the event again; the notifications already written are not written twice. Orchestration only:
 * the rules are in {@link CommunityNotificationService}.
 *
 * <p>Rule: SRS 3.7.3, 3.11.3; D-98, D-99; NSF-15.
 *
 * <p>Reference: Richardson, C. (2018). <i>Microservices Patterns</i>. Manning, ch. 3 (Transactional Outbox).
 */
@Component
public class CommunityNotificationListener {

    private final CommunityNotificationService notifications;

    public CommunityNotificationListener(CommunityNotificationService notifications) {
        this.notifications = notifications;
    }

    @ApplicationModuleListener
    public void on(PostCommented commented) {
        notifications.commented(commented);
    }

    @ApplicationModuleListener
    public void on(PostModerated moderated) {
        notifications.moderated(moderated);
    }

    @ApplicationModuleListener
    public void on(UserFollowed followed) {
        notifications.followed(followed);
    }
}
