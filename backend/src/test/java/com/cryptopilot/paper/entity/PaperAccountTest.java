package com.cryptopilot.paper.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cryptopilot.paper.model.enums.PositionMode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A paper account opens for its Trader in one-way mode.
 *
 * <p>Rule: TR-04; Q-T4.
 */
class PaperAccountTest {

    @Test
    void QT4_aNewAccount_isOneWay_andBelongsToItsTrader() {
        UUID trader = UUID.randomUUID();

        PaperAccount account = PaperAccount.open(trader);

        assertThat(account.getUserId()).isEqualTo(trader);
        assertThat(account.getPositionMode()).isEqualTo(PositionMode.ONE_WAY);
        assertThat(account.isNew()).isTrue();
    }

    @Test
    void anAccount_hasAnOwner() {
        assertThatNullPointerException().isThrownBy(() -> PaperAccount.open(null));
    }
}
