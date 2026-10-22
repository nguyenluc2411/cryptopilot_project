package com.cryptopilot.notification.service.impl;

import com.cryptopilot.community.event.PostCommented;
import com.cryptopilot.community.event.PostModerated;
import com.cryptopilot.community.model.enums.ResolutionAction;
import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.notification.repository.NotificationRepository;
import com.cryptopilot.notification.service.CommunityNotificationService;
import com.cryptopilot.user.AuthorCard;
import com.cryptopilot.user.UserApi;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The texts and recipients of the community's notifications (E-1). The SRS has no message for them, so the texts are
 * written here in the style of MSG22 to MSG28: one short sentence naming what happened.
 *
 * <p>Each notification has a source key made of the event's own ids, so an event delivered again finds its rows
 * written and writes nothing.
 *
 * <p>Dismissed reports are told to the reporters only: the post did not change, and the author learns nothing from
 * hearing it was reported.
 *
 * <p>Rule: SRS 3.7.3, 3.11.3; UC-30, UC-43; NSF-15.
 */
@Service
@RequiredArgsConstructor
public class CommunityNotificationServiceImpl implements CommunityNotificationService {

    /** The name shown for a member whose profile cannot be read. */
    static final String SOMEONE = "A member";

    private final NotificationWriter writer;
    private final NotificationRepository notifications;
    private final UserApi users;

    @Override
    @Transactional
    public void commented(PostCommented event) {
        if (event.postAuthorId().equals(event.commenterId())) {
            return;
        }
        String name = nameOf(event.commenterId());
        String content = event.threadId() == null
                ? name + " commented on your post."
                : name + " replied to a comment on your post.";
        writer.write(new NewNotification(
                event.postAuthorId(),
                NotificationType.COMMENT,
                "New comment on your post",
                content,
                NotificationReference.POST,
                event.postId(),
                "COMMENT:" + event.commentId()));
    }

    @Override
    @Transactional
    public void moderated(PostModerated event) {
        String decision = event.postId() + ":" + event.decidedAt().toEpochMilli();
        if (event.action() != ResolutionAction.DISMISS) {
            writer.write(new NewNotification(
                    event.authorId(),
                    NotificationType.MODERATION,
                    authorTitle(event.action()),
                    withNote(authorContent(event.action()), event.note()),
                    NotificationReference.POST,
                    event.postId(),
                    "MODERATION:" + decision + ":" + event.authorId()));
        }
        for (UUID reporter : event.reporterIds()) {
            writer.write(new NewNotification(
                    reporter,
                    NotificationType.MODERATION,
                    "Your report was reviewed",
                    "A moderator reviewed the post you reported: " + reporterOutcome(event.action()) + ".",
                    NotificationReference.POST,
                    event.postId(),
                    "MODERATION:" + decision + ":" + reporter));
        }
    }

    private String nameOf(UUID userId) {
        List<AuthorCard> cards = users.authors(List.of(userId));
        return cards.isEmpty() || cards.getFirst().displayName() == null
                ? SOMEONE
                : cards.getFirst().displayName();
    }

    private static String authorTitle(ResolutionAction action) {
        return switch (action) {
            case HIDE_POST -> "Your post was hidden";
            case REMOVE_POST -> "Your post was removed";
            case WARN_USER -> "You received a warning";
            case BAN_USER -> "Your account was banned";
            case DISMISS -> throw new IllegalArgumentException("A dismissal is not told to the author");
        };
    }

    private static String authorContent(ResolutionAction action) {
        return switch (action) {
            case HIDE_POST -> "A moderator hid your post because it breaks the community rules.";
            case REMOVE_POST -> "A moderator removed your post because it breaks the community rules.";
            case WARN_USER -> "A moderator warned you about your post. Please follow the community rules.";
            case BAN_USER -> "A moderator banned your account for your post, which is hidden.";
            case DISMISS -> throw new IllegalArgumentException("A dismissal is not told to the author");
        };
    }

    private static String reporterOutcome(ResolutionAction action) {
        return switch (action) {
            case HIDE_POST -> "it was hidden";
            case REMOVE_POST -> "it was removed";
            case DISMISS -> "it does not break the community rules";
            case WARN_USER -> "its author was warned";
            case BAN_USER -> "its author was banned";
        };
    }

    private static String withNote(String content, String note) {
        return note == null || note.isBlank() ? content : content + " Moderator's note: " + note.strip();
    }
}
