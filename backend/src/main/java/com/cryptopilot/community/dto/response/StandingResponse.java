package com.cryptopilot.community.dto.response;

/**
 * How an author counts in the community, shown beside their name on a post (D-99).
 *
 * @param influenceScore the influence score, see {@link com.cryptopilot.community.model.AuthorStanding}
 * @param followers the members who follow them
 * @param followedByMe whether the caller follows them, so the card offers Follow or Following
 */
public record StandingResponse(double influenceScore, long followers, boolean followedByMe) {}
