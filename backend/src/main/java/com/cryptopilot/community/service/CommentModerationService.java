package com.cryptopilot.community.service;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.community.dto.request.ModerationDecisionRequest;
import com.cryptopilot.community.dto.response.CommentModerationCaseResponse;
import com.cryptopilot.community.dto.response.CommentModerationDecisionResponse;
import com.cryptopilot.community.dto.response.CommentQueueItemResponse;
import com.cryptopilot.community.dto.response.ModeratedCommentResponse;
import java.util.UUID;

/**
 * The moderation of reported comments (D-99), beside that of posts ({@link ModerationService}): a queue of comments
 * with open reports, a case per comment, one decision for all its open reports, and restoring a hidden comment.
 * Implemented by {@link com.cryptopilot.community.service.impl.CommentModerationServiceImpl}.
 *
 * <p>Rule: UC-43; BR-57, BR-58; SRS 3.11.3; D-99.
 */
public interface CommentModerationService {

    /** One page of the comments with OPEN or REVIEWING reports, reported longest ago first. */
    PageResponse<CommentQueueItemResponse> queue(Integer page, Integer pageSize);

    /** Opens a reported comment: its OPEN reports become REVIEWING. MSG41 when it has no open report. */
    CommentModerationCaseResponse open(UUID moderator, UUID commentId);

    /**
     * Decides all open reports of a comment together and applies the decision: HIDE_COMMENT, REMOVE_COMMENT, DISMISS,
     * WARN_USER or BAN_USER.
     *
     * @throws com.cryptopilot.common.exception.BusinessException MSG01 for HIDE_POST or REMOVE_POST, MSG41 when the
     *     comment has no open report, MSG39 when BAN_USER names an administrator's comment
     */
    CommentModerationDecisionResponse decide(UUID moderator, UUID commentId, ModerationDecisionRequest request);

    /** Shows a hidden comment again; {@code COMMENT_NOT_HIDDEN} when it is not hidden, MSG41 when there is none. */
    ModeratedCommentResponse restore(UUID moderator, UUID commentId);
}
