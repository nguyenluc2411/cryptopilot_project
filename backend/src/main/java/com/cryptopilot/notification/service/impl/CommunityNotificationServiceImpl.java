package com.cryptopilot.notification.service.impl;

import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.community.event.CommentModerated;
import com.cryptopilot.community.event.MembersMentioned;
import com.cryptopilot.community.event.PostCommented;
import com.cryptopilot.community.event.PostModerated;
import com.cryptopilot.community.event.UserFollowed;
import com.cryptopilot.community.model.enums.ResolutionAction;
import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.notification.repository.NotificationRepository;
import com.cryptopilot.notification.service.CommunityNotificationService;
import com.cryptopilot.user.AuthorCard;
import com.cryptopilot.user.UserApi;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The texts and recipients of the community's notifications (E-1). The SRS has no message for them, so the texts are
 * written here in the style of MSG22 to MSG28: one short sentence naming what happened.
 *
 * <p>Each notification has a source key made of the event's own ids, so an event delivered again finds its rows
 * written and writes nothing. A follow is keyed by its time as well, so following again after an unfollow is a new
 * notification — unless the one before is still unread, which keeps a member who follows and unfollows in a loop from
 * filling the other's centre. A mention is keyed by the post or comment and the member, so editing the text again does
 * not tell a member twice. A reply tells the author of the comment replied to; when that is the post's author, they
 * get the reply notification alone.
 *
 * <p>Dismissed reports are told to the reporters only: the post did not change, and the author learns nothing from
 * hearing it was reported.
 *
 * <p>Rule: SRS 3.7.3, 3.11.3; UC-30, UC-43; D-99; NSF-15.
 */
@Service
@RequiredArgsConstructor
public class CommunityNotificationServiceImpl implements CommunityNotificationService {

    /** The name shown for a member whose profile cannot be read. */
    static final String SOMEONE = "A member";

    /** Most members one post or comment tells of a mention, whatever its edits (D-99). */
    static final int MENTIONS_PER_TEXT = 10;

    /** The scope of the lock under which the mentions of one post or comment are counted and written. */
    static final String MENTION_SCOPE = "community-mention";

    private final NotificationWriter writer;
    private final NotificationRepository notifications;
    private final UserApi users;
    private final UserLock lock;

    @Override
    @Transactional
    public void commented(PostCommented event) {
        UUID repliedTo = event.repliedToAuthorId();
        boolean tellsRepliedTo = repliedTo != null && !repliedTo.equals(event.commenterId());
        boolean tellsPostAuthor = !event.postAuthorId().equals(event.commenterId());
        if (!tellsRepliedTo && !tellsPostAuthor) {
            return;
        }
        String name = nameOf(event.commenterId());
        if (tellsPostAuthor && !event.postAuthorId().equals(repliedTo)) {
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
        if (tellsRepliedTo) {
            writer.write(new NewNotification(
                    repliedTo,
                    NotificationType.COMMENT,
                    "New reply to your comment",
                    name + " replied to your comment.",
                    NotificationReference.POST,
                    event.postId(),
                    "REPLY:" + event.commentId() + ":" + repliedTo));
        }
    }

    @Override
    @Transactional
    public void mentioned(MembersMentioned event) {
        String name = nameOf(event.authorId());
        String where = event.commentId() == null ? "a post" : "a comment";
        UUID source = event.commentId() == null ? event.postId() : event.commentId();
        String prefix = "MENTION:" + source + ":";
        // Two edits delivered at once would each count the members already told and both write up to the cap.
        lock.lock(MENTION_SCOPE, source);
        Set<String> told = new HashSet<>(notifications.findSourceKeysStartingWith(prefix));
        for (UUID member : event.mentionedIds()) {
            String key = prefix + member;
            if (told.contains(key)) {
                continue;
            }
            // Editing the text again and again does not tell more than this many members of one post or comment.
            if (told.size() >= MENTIONS_PER_TEXT) {
                break;
            }
            told.add(key);
            writer.write(new NewNotification(
                    member,
                    NotificationType.MENTION,
                    "You were mentioned",
                    name + " mentioned you in " + where + ".",
                    NotificationReference.POST,
                    event.postId(),
                    key));
        }
    }

    @Override
    @Transactional
    public void moderated(CommentModerated event) {
        String decision = event.commentId() + ":" + event.decidedAt().toEpochMilli();
        if (event.action() != ResolutionAction.DISMISS) {
            writer.write(new NewNotification(
                    event.authorId(),
                    NotificationType.MODERATION,
                    authorTitle(event.action(), "comment"),
                    withNote(authorContent(event.action(), "comment"), event.note()),
                    NotificationReference.POST,
                    event.postId(),
                    "MODERATION:" + decision + ":" + event.authorId()));
        }
        for (UUID reporter : event.reporterIds()) {
            writer.write(new NewNotification(
                    reporter,
                    NotificationType.MODERATION,
                    "Your report was reviewed",
                    "A moderator reviewed the comment you reported: " + reporterOutcome(event.action()) + ".",
                    NotificationReference.POST,
                    event.postId(),
                    "MODERATION:" + decision + ":" + reporter));
        }
    }

    @Override
    @Transactional
    public void moderated(PostModerated event) {
        String decision = event.postId() + ":" + event.decidedAt().toEpochMilli();
        if (event.action() != ResolutionAction.DISMISS) {
            writer.write(new NewNotification(
                    event.authorId(),
                    NotificationType.MODERATION,
                    authorTitle(event.action(), "post"),
                    withNote(authorContent(event.action(), "post"), event.note()),
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

    @Override
    @Transactional
    public void followed(UserFollowed event) {
        if (notifications.existsByUserIdAndTypeAndReferenceIdAndReadFalse(
                event.followeeId(), NotificationType.FOLLOW, event.followerId())) {
            return;
        }
        writer.write(new NewNotification(
                event.followeeId(),
                NotificationType.FOLLOW,
                "New follower",
                nameOf(event.followerId()) + " started following you.",
                NotificationReference.MEMBER,
                event.followerId(),
                "FOLLOW:" + event.followerId() + ":" + event.followeeId() + ":"
                        + event.followedAt().toEpochMilli()));
    }

    private String nameOf(UUID userId) {
        List<AuthorCard> cards = users.authors(List.of(userId));
        return cards.isEmpty() || cards.getFirst().displayName() == null
                ? SOMEONE
                : cards.getFirst().displayName();
    }

    private static String authorTitle(ResolutionAction action, String what) {
        return switch (action) {
            case HIDE_POST, HIDE_COMMENT -> "Your " + what + " was hidden";
            case REMOVE_POST, REMOVE_COMMENT -> "Your " + what + " was removed";
            case WARN_USER -> "You received a warning";
            case BAN_USER -> "Your account was banned";
            case DISMISS -> throw new IllegalArgumentException("A dismissal is not told to the author");
        };
    }

    private static String authorContent(ResolutionAction action, String what) {
        return switch (action) {
            case HIDE_POST, HIDE_COMMENT -> "A moderator hid your " + what + " because it breaks the community rules.";
            case REMOVE_POST, REMOVE_COMMENT ->
                "A moderator removed your " + what + " because it breaks the community rules.";
            case WARN_USER -> "A moderator warned you about your " + what + ". Please follow the community rules.";
            case BAN_USER -> "A moderator banned your account for your " + what + ", which is hidden.";
            case DISMISS -> throw new IllegalArgumentException("A dismissal is not told to the author");
        };
    }

    private static String reporterOutcome(ResolutionAction action) {
        return switch (action) {
            case HIDE_POST, HIDE_COMMENT -> "it was hidden";
            case REMOVE_POST, REMOVE_COMMENT -> "it was removed";
            case DISMISS -> "it does not break the community rules";
            case WARN_USER -> "its author was warned";
            case BAN_USER -> "its author was banned";
        };
    }

    private static String withNote(String content, String note) {
        return note == null || note.isBlank() ? content : content + " Moderator's note: " + note.strip();
    }
}
