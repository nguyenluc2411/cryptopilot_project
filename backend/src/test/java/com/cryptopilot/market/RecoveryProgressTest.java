package com.cryptopilot.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** How far a module has caught up with a recovering market (D-158). */
class RecoveryProgressTest {

    @Test
    void D158_progress_isDoneOnceEveryPairIs_andAddsUp() {
        assertThat(RecoveryProgress.NOTHING.done()).isTrue();
        assertThat(RecoveryProgress.NOT_ANSWERED.done()).isFalse();
        assertThat(new RecoveryProgress(1, 2).plus(new RecoveryProgress(3, 3))).isEqualTo(new RecoveryProgress(4, 5));
        assertThat(new RecoveryProgress(2, 2).done()).isTrue();
    }

    @Test
    void D158_progress_neverCountsMorePairsDoneThanThereAre() {
        assertThatThrownBy(() -> new RecoveryProgress(3, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecoveryProgress(-1, 2)).isInstanceOf(IllegalArgumentException.class);
    }
}
