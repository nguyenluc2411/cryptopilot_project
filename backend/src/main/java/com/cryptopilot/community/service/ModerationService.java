package com.cryptopilot.community.service;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.community.dto.request.ModerationDecisionRequest;
import com.cryptopilot.community.dto.response.ModerationCaseResponse;
import com.cryptopilot.community.dto.response.ModerationDecisionResponse;
import com.cryptopilot.community.dto.response.PostDetailResponse;
import com.cryptopilot.community.dto.response.QueueItemResponse;
import java.util.UUID;

/**
 * The moderation of reported posts (UC-43, SCR-36). Every decision is recorded in the audit log (BR-57). Implemented
 * by {@link com.cryptopilot.community.service.impl.ModerationServiceImpl}.
 *
 * <p>Rule: UC-43; BR-06, BR-47, BR-57, BR-58; SRS 3.11.3; D-48.
 */
public interface ModerationService {

    /** One page of the posts with OPEN or REVIEWING reports, reported longest ago first. */
    PageResponse<QueueItemResponse> queue(UUID moderator, Integer page, Integer pageSize);

    /** Opens a reported post: its OPEN reports become REVIEWING. MSG41 when it has no open report. */
    ModerationCaseResponse open(UUID moderator, UUID postId);

    /**
     * Decides all open reports of a post together and applies the decision.
     *
     * @throws com.cryptopilot.common.exception.BusinessException MSG41 when the post has no open report, MSG39 when
     *     BAN_USER names an administrator's post
     */
    ModerationDecisionResponse decide(UUID moderator, UUID postId, ModerationDecisionRequest request);

    /** Shows a hidden post again; {@code POST_NOT_HIDDEN} when it is not hidden, MSG41 when there is none. */
    PostDetailResponse restore(UUID moderator, UUID postId);

    /** Any post, whatever its status (BR-47: Admin sees hidden and deleted posts). */
    PostDetailResponse post(UUID moderator, UUID postId);
}
