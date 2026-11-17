package com.cryptopilot.community.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.cryptopilot.community.dto.response.CoinSentimentResponse;
import com.cryptopilot.community.service.PostService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The community's leaning per coin is served from the last computed answer: computed once before the first refresh,
 * replaced by each refresh, and kept when a refresh fails.
 *
 * <p>Rule: D-125.
 */
class CoinSentimentServiceImplTest {

    private final PostService posts = mock(PostService.class);
    private final CoinSentimentServiceImpl service = new CoinSentimentServiceImpl(posts);

    @Test
    void theFirstRequestComputesIt_andTheNextOnesReadItUntilARefresh() {
        List<CoinSentimentResponse> first = List.of(new CoinSentimentResponse("BTC", 3, 1));
        List<CoinSentimentResponse> second = List.of(new CoinSentimentResponse("BTC", 4, 1));
        doReturn(first).when(posts).coinSentiment();

        assertThat(service.current()).isEqualTo(first);
        doReturn(second).when(posts).coinSentiment();
        assertThat(service.current()).isEqualTo(first);
        verify(posts, times(1)).coinSentiment();

        service.refresh();
        assertThat(service.current()).isEqualTo(second);
    }

    @Test
    void aFailedRefresh_keepsTheLastAnswer() {
        List<CoinSentimentResponse> known = List.of(new CoinSentimentResponse("ETH", 1, 2));
        doReturn(known).when(posts).coinSentiment();
        service.refresh();

        doThrow(new IllegalStateException("database down")).when(posts).coinSentiment();
        service.refresh();

        assertThat(service.current()).isEqualTo(known);
    }
}
