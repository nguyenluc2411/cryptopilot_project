package com.cryptopilot.ai;

/**
 * What the {@code ai} module offers for NSF-11: one article summarized by the AI Service. The caller chooses the
 * model, the instructions and the settings from its own configuration; this module only carries the request to the
 * AI Service, which alone holds the provider's key (ADR-004, TECHNICAL_DESIGN 7.14).
 *
 * <p>Rule: NSF-11; ADR-004.
 */
public interface NewsSummaryApi {

    /**
     * The AI Service's summary and sentiment of one article.
     *
     * @throws AiServiceException when the AI Service is not configured, cannot be reached, or cannot produce an
     *     answer; {@link AiServiceException#retryable()} says whether the same request may succeed later
     */
    NewsSummaryResult summarize(NewsSummaryRequest request);
}
