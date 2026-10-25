package com.cryptopilot.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cryptopilot.ai.NewsSummaryRequest;
import com.cryptopilot.ai.NewsSummaryResult;
import com.cryptopilot.ai.client.AiServiceClient;
import org.junit.jupiter.api.Test;

/**
 * The news module's way into the AI Service hands the request to the client as it is and its answer back.
 *
 * <p>Rule: NSF-11; ADR-004.
 */
class NewsSummaryApiImplTest {

    @Test
    void NSF11_theRequestGoesToTheAiService_andItsAnswerComesBack() {
        AiServiceClient client = mock(AiServiceClient.class);
        NewsSummaryRequest request = new NewsSummaryRequest("m", "p", 0.2, 256, "t", "s", "x", null);
        NewsSummaryResult result = new NewsSummaryResult("title", "summary", "NEUTRAL", "m", 10, 5);
        when(client.summarizeNews(request)).thenReturn(result);

        assertThat(new NewsSummaryApiImpl(client).summarize(request)).isSameAs(result);
    }
}
