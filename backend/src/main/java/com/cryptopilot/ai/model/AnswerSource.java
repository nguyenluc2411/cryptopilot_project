package com.cryptopilot.ai.model;

/**
 * One article an answer cited, as stored in {@code ai_message.sources} and shown under the answer (T-074).
 *
 * @param ref the number the answer cites it by, {@code [ref]}
 * @param origin where the article came from: {@code SYSTEM} for the outlets the news module reads
 * @param headline the headline
 * @param outlet the news source
 * @param publishedAt when it was published, ISO-8601 as the context carried it, or {@code null}
 * @param url the original article
 */
public record AnswerSource(int ref, String origin, String headline, String outlet, String publishedAt, String url) {

    /** The origin of the articles the news module collected. */
    public static final String SYSTEM = "SYSTEM";
}
