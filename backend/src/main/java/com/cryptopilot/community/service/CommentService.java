package com.cryptopilot.community.service;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.community.dto.request.CommentRequest;
import com.cryptopilot.community.dto.response.CommentResponse;
import java.util.UUID;

/**
 * The comments of SCR-25 (UC-30). A post the caller may not see answers MSG41, and so do its comments; another
 * Trader's comment the caller tries to change answers MSG41 too. Implemented by
 * {@link com.cryptopilot.community.service.impl.CommentServiceImpl}.
 *
 * <p>Rule: UC-30; BR-47; SRS 3.7.3; D-48.
 */
public interface CommentService {

    /** One page of a post's threads, oldest first, each with its replies. */
    PageResponse<CommentResponse> list(UUID caller, UUID postId, Integer page, Integer pageSize);

    /** Publishes a comment, or a reply when the request names a published comment of the post. */
    CommentResponse create(UUID caller, UUID postId, CommentRequest request);

    /** Replaces the text of one of the caller's comments that is not deleted. */
    CommentResponse update(UUID caller, UUID commentId, CommentRequest request);

    /** Deletes one of the caller's comments (BR-47). */
    void delete(UUID caller, UUID commentId);
}
