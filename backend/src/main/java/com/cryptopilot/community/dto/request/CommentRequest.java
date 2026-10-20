package com.cryptopilot.community.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The comment box of SCR-25: a new comment, a reply, or the new text of an own comment (UC-30). Leading and trailing
 * white space is removed before the length is checked.
 *
 * <p>Rule: UC-30; SRS 3.7.3.
 *
 * @param content 1 to 2 000 characters
 * @param parentCommentId the comment replied to, or {@code null} for a new thread; ignored on an edit
 */
public record CommentRequest(
        @NotBlank(message = "MSG01") @Size(max = 2_000, message = "MSG01")
        String content,

        UUID parentCommentId) {

    public CommentRequest {
        content = content == null ? null : content.strip();
    }
}
