package com.cryptopilot.market.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.event.PairMarketEnabled;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.MarketReferenceService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A refresh at start-up and every ten minutes, one at a time; the pairs of a batch brought into service make one read
 * of the supplies once they have settled, put off again while a refresh is in progress.
 *
 * <p>Rule: SRS 3.3.1; UC-45.
 */
class MarketReferenceJobTest {

    private static final Instant NOW = Instant.parse("2026-11-21T11:00:00Z");

    private final MarketReferenceService reference = mock(MarketReferenceService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ScheduledFuture<?> future = mock(ScheduledFuture.class);
    private final MarketReferenceJob job =
            new MarketReferenceJob(reference, scheduler, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void startsNow_andRefreshesEveryTenMinutes() {
        job.start();

        ArgumentCaptor<Runnable> run = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(run.capture(), eq(NOW), eq(MarketReferenceJob.EVERY));
        run.getValue().run();
        verify(reference).refresh();
    }

    @Test
    void aBatchOfPairsMakesOneReadOfTheSupplies_afterTheyHaveSettled() {
        doReturnFuture();

        job.onPairMarketEnabled(new PairMarketEnabled(UUID.randomUUID(), "AEROUSDT", MarketType.SPOT));
        job.onPairMarketEnabled(new PairMarketEnabled(UUID.randomUUID(), "ZROUSDT", MarketType.SPOT));

        ArgumentCaptor<Runnable> read = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(2)).schedule(read.capture(), eq(NOW.plus(MarketReferenceJob.SETTLE)));
        verify(future).cancel(false);
        verify(reference, never()).refreshSupplies();

        read.getValue().run();
        verify(reference).refreshSupplies();
        verify(reference, never()).refresh();
    }

    /** Supplies read less than five minutes ago are not read again: the read waits until five minutes have passed. */
    @Test
    void aReadOfTheSupplies_keepsFiveMinutesFromTheLastOne() {
        doReturnFuture();
        Instant last = NOW.minus(Duration.ofMinutes(2));
        when(reference.suppliesReadAt()).thenReturn(Optional.of(last));

        job.runSupplies();

        verify(reference, never()).refreshSupplies();
        verify(scheduler).schedule(any(Runnable.class), eq(last.plus(MarketReferenceJob.SUPPLIES_FLOOR)));

        when(reference.suppliesReadAt()).thenReturn(Optional.of(NOW.minus(MarketReferenceJob.SUPPLIES_FLOOR)));
        job.runSupplies();
        verify(reference).refreshSupplies();
    }

    /** Inside a transaction: the read is scheduled once it commits, never when it rolls back. */
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
            verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(scheduler).schedule(any(Runnable.class), eq(NOW.plus(MarketReferenceJob.SETTLE)));
    }

    /** A scheduler that refuses (shutting down) is logged, never thrown at the publisher. */
    @Test
    void aRefusingScheduler_isNotThrownAtTheEnable() {
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(new IllegalStateException("shut down"));

        job.onPairMarketEnabled(new PairMarketEnabled(UUID.randomUUID(), "AEROUSDT", MarketType.SPOT));

        verify(reference, never()).refreshSupplies();
    }

    @Test
    void aReadOfTheSuppliesDuringARefresh_isPutOff() {
        doReturnFuture();
        doAnswer(call -> onAnotherThread(job::runSupplies)).when(reference).refresh();

        job.run();

        verify(reference, never()).refreshSupplies();
        verify(scheduler).schedule(any(Runnable.class), eq(NOW.plus(MarketReferenceJob.SETTLE)));
    }

    @Test
    void aRefreshDuringARefresh_isSkipped() {
        doAnswer(call -> onAnotherThread(job::run)).when(reference).refresh();

        job.run();

        verify(reference, times(1)).refresh();
    }

    /** The scheduler runs the jobs on several threads; the lock is held per thread. */
    private static Object onAnotherThread(Runnable work) throws InterruptedException {
        Thread other = new Thread(work);
        other.start();
        other.join();
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doReturnFuture() {
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn((ScheduledFuture) future);
    }
}
