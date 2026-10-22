package com.cryptopilot.paper.job;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.event.SpotPairStoppedTrading;
import com.cryptopilot.market.model.enums.ExchangeStatus;
import com.cryptopilot.paper.service.impl.PairStopCancellation;
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
    private final PairStopListener listener = new PairStopListener(cancellation);

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
}
