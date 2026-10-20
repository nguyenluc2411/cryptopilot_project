package com.cryptopilot.community.service;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.community.dto.request.PostRequest;
import com.cryptopilot.community.dto.response.PostDetailResponse;
import com.cryptopilot.community.dto.response.PostSummaryResponse;
import java.util.UUID;

/**
 * The official posts of the administration console (UC-44): any administrator publishes, edits and deletes them, and
 * they are pinned above the community feed (BR-61). Implemented by
 * {@link com.cryptopilot.community.service.impl.AnnouncementServiceImpl}.
 *
 * <p>Rule: UC-44; BR-47, BR-61; D-48.
 */
public interface AnnouncementService {

    /** One page of the announcements that are not deleted, hidden ones included, newest first. */
    PageResponse<PostSummaryResponse> list(UUID admin, Integer page, Integer pageSize);

    /** Publishes an announcement written by {@code admin}. */
    PostDetailResponse create(UUID admin, PostRequest request);

    /** Replaces the content of an announcement that is not deleted; MSG41 when there is none. */
    PostDetailResponse update(UUID admin, UUID postId, PostRequest request);

    /** Deletes an announcement (BR-47); MSG41 when there is none. */
    void delete(UUID admin, UUID postId);
}
