package com.cryptopilot.community.dto.response;

import java.util.UUID;

/**
 * Who wrote a post, as SCR-24 and SCR-25 show it.
 *
 * @param userId the account
 * @param displayName the profile's name, or {@code null} for an account without a profile
 * @param avatarUrl the avatar image, or {@code null}
 */
public record AuthorResponse(UUID userId, String displayName, String avatarUrl) {}
