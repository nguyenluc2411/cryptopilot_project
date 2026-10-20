package com.cryptopilot.community.dto.response;

/**
 * Where a like or a save stands after the caller's click, so the client updates the button and the count at once
 * (SRS 3.7.3).
 *
 * <p>Rule: BR-45.
 *
 * @param active whether the caller now likes or saves it
 * @param count how many Traders like or save it now
 */
public record EngagementResponse(boolean active, long count) {}
