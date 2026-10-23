package com.cryptopilot.community.model;

/**
 * What a member did in the community, for their public page (D-99).
 *
 * @param posts their published posts
 * @param likesReceived the likes their published posts received
 */
public record MemberActivity(long posts, long likesReceived) {}
