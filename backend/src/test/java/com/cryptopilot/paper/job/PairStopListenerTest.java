package com.cryptopilot.paper.job;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.PairMarketDisabled;
import com.cryptopilot.market.event.SpotPairStoppedTrading;
import com.cryptopilot.market.model.enums.ExchangeStatus;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.service.impl.FuturesPairStop;
import com.cryptopilot.paper.service.impl.PairStopCancellation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The listener of a pair that stopped trading: one account at a time, an account that fails does not stop the others,
 * and the delivery then fails so the outbox delivers it again.
 *
 * <p>Rule: TR-02; NSF-01; ADR-010.
 */
class PairStopListenerTest {

    private static final UUID PAIR = UUID.randomUUID();
    private static final SpotPairStoppedTrading STOPPED =
            new SpotPairStoppedTrading(PAIR, "XYZUSDT", ExchangeStatus.NOT_TRADING);

    private final PairStopCancellation cancellation = mock(PairStopCancellation.class);
    private final FuturesPairStop futures = mock(FuturesPairStop.class);
    private final PairStopListener listener = new PairStopListener(cancellation, futures);

    @Test
    void TR02_anAccountThatFails_doesNotStopTheOthers_andTheDeliveryFailsForARetry() {
        UUID first = UUID.randomUUID();
        UUID failing = UUID.randomUUID();
        UUID last = UUID.randomUUID();
        when(cancellation.accountsWithOrders(PAIR)).thenReturn(List.of(first, failing, last));
        when(cancellation.cancel(failing, PAIR)).thenThrow(new IllegalStateException("database down"));

        assertThatIllegalStateException().isThrownBy(() -> listener.on(STOPPED)).withMessageContaining("1 accounts");
        verify(cancellation).cancel(first, PAIR);
        verify(cancellation).cancel(last, PAIR);
    }

    @Test
    void TR02_everyAccountCancelled_endsTheDelivery() {
        when(cancellation.accountsWithOrders(PAIR)).thenReturn(List.of(UUID.randomUUID()));

        assertThatCode(() -> listener.on(STOPPED)).doesNotThrowAnyException();
    }

    /** TR-46: a Futures market taken out of service ends the Futures of each account, one failing not the others. */
    @Test
    void TR46_aFuturesMarketDisabled_endsEachAccountsFutures_andRetriesAFailure() {
        UUID first = UUID.randomUUID();
        UUID failing = UUID.randomUUID();
        PairMarketDisabled disabled = new PairMarketDisabled(PAIR, "XYZUSDT", MarketType.FUTURES, Instant.now());
        when(futures.accountsOn(PAIR)).thenReturn(List.of(first, failing));
        when(futures.stop(failing, PAIR)).thenThrow(new IllegalStateException("no mark"));

        assertThatIllegalStateException()
                .isThrownBy(() -> listener.on(disabled))
                .withMessageContaining("1 accounts");
        verify(futures).stop(first, PAIR);

        when(futures.accountsOn(PAIR)).thenReturn(List.of(first));
        assertThatCode(() -> listener.on(disabled)).doesNotThrowAnyException();
    }

    @Test
    void TR02_aSpotMarketDisabled_cancelsItsOrders() {
        UUID trader = UUID.randomUUID();
        when(cancellation.accountsWithOrders(PAIR)).thenReturn(List.of(trader));

        listener.on(new PairMarketDisabled(PAIR, "XYZUSDT", MarketType.SPOT, Instant.now()));

        verify(cancellation).cancel(trader, PAIR);
    }
}
