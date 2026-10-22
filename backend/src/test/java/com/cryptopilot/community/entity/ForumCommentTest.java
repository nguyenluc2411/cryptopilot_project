package com.cryptopilot.community.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The rules a comment enforces by itself: the length of SRS 3.7.3, threads one level deep and the soft delete.
 *
 * <p>Rule: UC-30; BR-47; SRS 3.7.3; D-98.
 */
class ForumCommentTest {

    private static final UUID POST = UUID.randomUUID();
    private static final UUID AUTHOR = UUID.randomUUID();

    @Test
    void UC30_aReplyToAReply_joinsTheThreadOfItsFirstComment() {
        ForumComment first = ForumComment.on(POST, AUTHOR, "first");
        ForumComment reply = ForumComment.replyTo(first, AUTHOR, "reply");
        ForumComment replyToReply = ForumComment.replyTo(reply, AUTHOR, "reply to reply");

        assertThat(first.getParentCommentId()).isNull();
        assertThat(reply.getParentCommentId()).isEqualTo(first.getId());
        assertThat(replyToReply.getParentCommentId()).isEqualTo(first.getId());
        assertThat(replyToReply.getPostId()).isEqualTo(POST);
    }

    @Test
    void UC30_aCommentHasOneTo2000Characters_withoutSurroundingSpace() {
        assertThat(ForumComment.on(POST, AUTHOR, "  ok  ").getContent()).isEqualTo("ok");
        assertThat(ForumComment.on(POST, AUTHOR, "x".repeat(2_000)).getContent())
                .hasSize(2_000);
        assertThatThrownBy(() -> ForumComment.on(POST, AUTHOR, "   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ForumComment.on(POST, AUTHOR, "x".repeat(2_001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void BR47_aDeletedComment_isNoLongerPublished_andCannotBeEdited() {
        ForumComment comment = ForumComment.on(POST, AUTHOR, "text");

        comment.delete();

        assertThat(comment.isDeleted()).isTrue();
        assertThat(comment.isPublished()).isFalse();
        assertThatThrownBy(() -> comment.edit("again")).isInstanceOf(IllegalStateException.class);
    }
}
