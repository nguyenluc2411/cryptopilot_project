package com.cryptopilot.market.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.client.CoinPaprikaException;
import com.cryptopilot.market.event.PairMarketEnabled;
import com.cryptopilot.market.event.SymbolsSynchronised;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.CoinDescriptionService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Two market events make one run, put off until both have settled, and so do the pairs of a batch an administrator
 * brings into service; a 429 is retried once, any other failure waits.
 */
class CoinDescriptionJobTest {

    private static final Instant NOW = Instant.parse("2026-11-17T00:05:00Z");

    private final CoinDescriptionService descriptions = mock(CoinDescriptionService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ScheduledFuture<?> future = mock(ScheduledFuture.class);
    private final CoinDescriptionJob job =
            new CoinDescriptionJob(descriptions, scheduler, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void bothMarketsMakeOneRunAfterTheyHaveSettled() {
        doReturnFuture();

        job.onSymbolsSynchronised(new SymbolsSynchronised(MarketType.SPOT));
        job.onSymbolsSynchronised(new SymbolsSynchronised(MarketType.FUTURES));

        verify(scheduler, org.mockito.Mockito.times(2))
                .schedule(any(Runnable.class), eq(NOW.plus(CoinDescriptionJob.SETTLE)));
        verify(future).cancel(false);
        verify(descriptions, never()).describeCoins();
    }

    @Test
    void aBatchOfPairsBroughtIntoServiceMakesOneRunAfterTheyHaveSettled() {
        doReturnFuture();

        job.onPairMarketEnabled(new PairMarketEnabled(UUID.randomUUID(), "AEROUSDT", MarketType.SPOT));
        job.onPairMarketEnabled(new PairMarketEnabled(UUID.randomUUID(), "ZROUSDT", MarketType.SPOT));

        ArgumentCaptor<Runnable> run = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, org.mockito.Mockito.times(2))
                .schedule(run.capture(), eq(NOW.plus(CoinDescriptionJob.SETTLE)));
        verify(future).cancel(false);
        verify(descriptions, never()).describeCoins();

        run.getValue().run();
        verify(descriptions).describeCoins();
    }

    /** Hourly, from an hour after start-up, for the coins a run left without a logo; a 429 there is not retried. */
    @Test
    void theHourlyRun_startsAnHourAfterStartUp() {
        job.start();

        ArgumentCaptor<Runnable> hourly = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler)
                .scheduleAtFixedRate(
                        hourly.capture(), eq(NOW.plus(CoinDescriptionJob.EVERY)), eq(CoinDescriptionJob.EVERY));
        when(descriptions.describeCoins()).thenThrow(new CoinPaprikaException("slow down", 402, null));
        hourly.getValue().run();
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    /** Inside a transaction: the run is scheduled once it commits, never when it rolls back. */
    @Test
    void aPairTurnedOn_waitsForTheCommit_andARollbackSchedulesNothing() {
        doReturnFuture();
        PairMarketEnabled enabled = new PairMarketEnabled(UUID.randomUUID(), "AEROUSDT", MarketType.SPOT);

        TransactionSynchronizationManager.initSynchronization();
        try {
            job.onPairMarketEnabled(enabled);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));

        TransactionSynchronizationManager.initSynchronization();
        try {
            job.onPairMarketEnabled(enabled);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(scheduler).schedule(any(Runnable.class), eq(NOW.plus(CoinDescriptionJob.SETTLE)));
    }

    /** A scheduler that refuses (shutting down) is logged, never thrown at the publisher. */
    @Test
    void aRefusingScheduler_isNotThrownAtTheEnable() {
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(new IllegalStateException("shut down"));

        job.onPairMarketEnabled(new PairMarketEnabled(UUID.randomUUID(), "AEROUSDT", MarketType.SPOT));

        verify(descriptions, never()).describeCoins();
    }

    @Test
    void aRateLimitIsRetriedOnceAndThenWaits() {
        doReturnFuture();
        when(descriptions.describeCoins()).thenThrow(new CoinPaprikaException("slow down", 429, null));

        assertThat(job.run(true)).isEqualTo(-1);

        ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(retry.capture(), eq(NOW.plus(CoinDescriptionJob.RATE_LIMIT_WAIT)));
        retry.getValue().run();
        verify(scheduler, org.mockito.Mockito.times(1)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void anyOtherFailureWaitsForTheNextSynchronisation() {
        when(descriptions.describeCoins()).thenThrow(new CoinPaprikaException("down", 503, null));

        assertThat(job.run(true)).isEqualTo(-1);

        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void aRunSaysHowManyCoinsChanged() {
        when(descriptions.describeCoins()).thenReturn(7);

        assertThat(job.run(true)).isEqualTo(7);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doReturnFuture() {
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn((ScheduledFuture) future);
    }
}
