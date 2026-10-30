package com.cryptopilot.paper.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.paper.config.PaperPairStopSweepProperties;
import com.cryptopilot.paper.service.impl.FuturesPairStop;
import com.cryptopilot.paper.service.impl.PairStopCancellation;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

/**
 * The sweep's Futures side (TR-46): a pair still in service is left alone, and on one out of service each account's
 * Futures are ended, one failing not the others.
 */
class PaperPairStopSweepTest {

    private final PairStopCancellation spot = mock(PairStopCancellation.class);
    private final FuturesPairStop futures = mock(FuturesPairStop.class);
    private final PaperPairStopSweep sweep = new PaperPairStopSweep(
            spot, futures, mock(TaskScheduler.class), new PaperPairStopSweepProperties(true, Duration.ofMinutes(5)));

    @Test
    void TR46_theFuturesOfAPairOutOfService_areEnded_eachAccountOnItsOwn() {
        UUID trading = UUID.randomUUID();
        UUID stopped = UUID.randomUUID();
        UUID ended = UUID.randomUUID();
        UUID failing = UUID.randomUUID();
        when(futures.pairsInUse()).thenReturn(List.of(trading, stopped));
        when(futures.outOfService(stopped)).thenReturn(true);
        when(futures.accountsOn(stopped)).thenReturn(List.of(failing, ended));
        when(futures.stop(failing, stopped)).thenThrow(new IllegalStateException("no mark"));
        when(futures.stop(ended, stopped)).thenReturn(3);

        assertThat(sweep.run()).isEqualTo(3);

        verify(futures, never()).accountsOn(trading);
    }
}
