package com.cryptopilot.community.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.cryptopilot.community.config.CoinSentimentProperties;
import com.cryptopilot.community.dto.response.CoinSentimentResponse;
import com.cryptopilot.community.service.PostService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The community's leaning per coin is served from the last computed answer: computed once before any computation,
 * replaced by each refresh, and kept when a recompute fails; with the job off, a request recomputes an answer older
 * than the interval.
 *
 * <p>Rule: D-125.
 */
class CoinSentimentServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-17T06:00:00Z");

    private final PostService posts = mock(PostService.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final CoinSentimentServiceImpl service = service(true);

    private final List<CoinSentimentResponse> first = List.of(new CoinSentimentResponse("BTC", 3, 1));
    private final List<CoinSentimentResponse> second = List.of(new CoinSentimentResponse("BTC", 4, 1));

    @Test
    void theFirstRequestComputesIt_andTheNextOnesReadItUntilARefresh() {
        doReturn(first).when(posts).coinSentiment();

        assertThat(service.current()).isEqualTo(first);
        doReturn(second).when(posts).coinSentiment();
        clock.advance(Duration.ofDays(1));
        // The job is on: however old, a request only reads.
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

    @Test
    void withTheJobOff_aRequestRecomputesAnAnswerOlderThanTheInterval_andNotBefore() {
        CoinSentimentServiceImpl off = service(false);
        doReturn(first).when(posts).coinSentiment();
        assertThat(off.current()).isEqualTo(first);

        doReturn(second).when(posts).coinSentiment();
        clock.advance(Duration.ofSeconds(60));
        assertThat(off.current()).isEqualTo(first);
        verify(posts, times(1)).coinSentiment();

        clock.advance(Duration.ofSeconds(1));
        assertThat(off.current()).isEqualTo(second);
        assertThat(off.current()).isEqualTo(second);
        verify(posts, times(2)).coinSentiment();
    }

    @Test
    void withTheJobOff_aFailedRecompute_answersThePreviousOne_butAFailedFirstIsTheCallersError() {
        CoinSentimentServiceImpl off = service(false);
        doThrow(new IllegalStateException("database down")).when(posts).coinSentiment();
        assertThatThrownBy(off::current).isInstanceOf(IllegalStateException.class);

        doReturn(first).when(posts).coinSentiment();
        assertThat(off.current()).isEqualTo(first);
        clock.advance(Duration.ofMinutes(5));
        doThrow(new IllegalStateException("database down")).when(posts).coinSentiment();

        assertThat(off.current()).isEqualTo(first);
    }

    private CoinSentimentServiceImpl service(boolean jobEnabled) {
        return new CoinSentimentServiceImpl(
                posts, new CoinSentimentProperties(jobEnabled, Duration.ofSeconds(60)), clock);
    }

    /** A clock a test moves. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
