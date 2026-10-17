package com.cryptopilot.paper.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.paper.model.enums.WalletType;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A paper balance never goes below zero, free or locked, and a refused change leaves it as it was.
 *
 * <p>Rule: TR-04.
 */
class PaperBalanceTest {

    private final PaperBalance balance = PaperBalance.empty(UUID.randomUUID(), WalletType.SPOT, UUID.randomUUID());

    @Test
    void aNewBalance_holdsNothing() {
        assertThat(balance.getFreeAmount()).isZero();
        assertThat(balance.getLockedAmount()).isZero();
        assertThat(balance.total()).isZero();
    }

    @Test
    void lockingAndUnlocking_movesBetweenFreeAndLocked_withoutChangingTheTotal() {
        balance.credit(new BigDecimal("100"));

        balance.lock(new BigDecimal("60"), "USDT");
        assertThat(balance.getFreeAmount()).isEqualByComparingTo("40");
        assertThat(balance.getLockedAmount()).isEqualByComparingTo("60");
        assertThat(balance.total()).isEqualByComparingTo("100");

        balance.unlock(new BigDecimal("10"));
        assertThat(balance.getFreeAmount()).isEqualByComparingTo("50");
        assertThat(balance.getLockedAmount()).isEqualByComparingTo("50");
    }

    @Test
    void spendingLocked_lowersTheTotal() {
        balance.credit(new BigDecimal("100"));
        balance.lock(new BigDecimal("60"), "USDT");

        balance.spendLocked(new BigDecimal("60"));

        assertThat(balance.getLockedAmount()).isZero();
        assertThat(balance.total()).isEqualByComparingTo("40");
    }

    @Test
    void debitingTheWholeFreeAmount_isAllowed() {
        balance.credit(new BigDecimal("0.00000001"));

        balance.debit(new BigDecimal("0.00000001"), "USDT");

        assertThat(balance.getFreeAmount()).isZero();
    }

    @Test
    void debitingMoreThanIsFree_isRefused_andChangesNothing() {
        balance.credit(new BigDecimal("100"));
        balance.lock(new BigDecimal("30"), "USDT");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> balance.debit(new BigDecimal("70.00000001"), "USDT"))
                .satisfies(refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.PAPER_INSUFFICIENT_BALANCE));
        assertThat(balance.getFreeAmount()).isEqualByComparingTo("70");
        assertThat(balance.getLockedAmount()).isEqualByComparingTo("30");
    }

    @Test
    void lockingMoreThanIsFree_isRefused() {
        balance.credit(BigDecimal.TEN);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> balance.lock(BigDecimal.valueOf(11), "USDT"));
        assertThat(balance.getFreeAmount()).isEqualByComparingTo("10");
    }

    @Test
    void releasingMoreThanIsLocked_isADefect() {
        balance.credit(BigDecimal.TEN);
        balance.lock(BigDecimal.ONE, "USDT");

        assertThatIllegalStateException().isThrownBy(() -> balance.unlock(BigDecimal.TWO));
        assertThatIllegalStateException().isThrownBy(() -> balance.spendLocked(BigDecimal.TWO));
    }

    @Test
    void anAmount_isPositive() {
        assertThatIllegalArgumentException().isThrownBy(() -> balance.credit(BigDecimal.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> balance.credit(new BigDecimal("-1")));
    }
}
