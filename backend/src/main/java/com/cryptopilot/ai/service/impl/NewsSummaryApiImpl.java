package com.cryptopilot.ai.service.impl;

import com.cryptopilot.ai.NewsSummaryApi;
import com.cryptopilot.ai.NewsSummaryRequest;
import com.cryptopilot.ai.NewsSummaryResult;
import com.cryptopilot.ai.client.AiServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link NewsSummaryApi} over the AI Service client. Holds no rule of its own: what to send and what to keep is the news
 * module's (NSF-11), the call itself the AI Service's.
 *
 * <p>Rule: NSF-11; ADR-004.
 */
@Service
@RequiredArgsConstructor
public class NewsSummaryApiImpl implements NewsSummaryApi {

    private final AiServiceClient client;

    @Override
    public NewsSummaryResult summarize(NewsSummaryRequest request) {
        return client.summarizeNews(request);
    }
}
