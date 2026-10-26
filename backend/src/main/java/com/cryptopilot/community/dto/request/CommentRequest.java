package com.cryptopilot.community.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * The comment box of SCR-25: a new comment, a reply, or the new text of an own comment (UC-30). Leading and trailing
 * white space is removed before the length is checked.
 *
 * <p>Rule: UC-30; SRS 3.7.3.
 *
 * @param content 1 to 2 000 characters
 * @param parentCommentId the comment replied to, or {@code null} for a new thread; ignored on an edit
 * @param mentions the members the text mentions (D-99), at most 10, each told once; {@code null} for none
 */
public record CommentRequest(
        @NotBlank(message = "MSG01") @Size(max = 2_000, message = "MSG01")
        String content,

        UUID parentCommentId,

        @Size(max = 10, message = "MSG01") List<@NotNull(message = "MSG01") UUID> mentions) {

    public CommentRequest {
        content = content == null ? null : content.strip();
        mentions = mentions == null ? List.of() : mentions.stream().distinct().toList();
    }

    /** A comment or a reply that mentions nobody. */
    public CommentRequest(String content, UUID parentCommentId) {
        this(content, parentCommentId, List.of());
    }
}
