package com.cryptopilot.ai.dto.response;

import java.time.Instant;

/**
 * An article an answer cites as {@code [ref]}, shown under it so the Trader can read the original (T-074).
 *
 * @param ref the number the answer cites it by
 * @param origin where it came from: {@code SYSTEM} for the outlets the news module reads
 * @param headline the headline
 * @param outlet the news source
 * @param publishedAt when it was published, or {@code null}
 * @param url the original article
 */
public record MessageSourceResponse(
        int ref, String origin, String headline, String outlet, Instant publishedAt, String url) {}
