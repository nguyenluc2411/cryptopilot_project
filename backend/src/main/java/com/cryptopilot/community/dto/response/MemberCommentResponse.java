package com.cryptopilot.community.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * One comment on the Comments tab of a member page (D-99, as on CoinMarketCap Community), with the post it is on so
 * the reader can open it.
 *
 * <p>Rule: D-99.
 *
 * @param commentId the comment
 * @param postId the post it is on
 * @param reply whether it answers another comment
 * @param content its text
 * @param likes the likes it received
 * @param createdAt when it was written
 * @param postTitle the title of the post
 * @param postExcerpt the start of the post's text, at most 160 characters
 */
public record MemberCommentResponse(
        UUID commentId,
        UUID postId,
        boolean reply,
        String content,
        long likes,
        Instant createdAt,
        String postTitle,
        String postExcerpt) {}
