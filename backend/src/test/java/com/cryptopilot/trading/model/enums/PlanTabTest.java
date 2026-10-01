package com.cryptopilot.trading.model.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The tabs of SCR-16 cover every status exactly once. */
class PlanTabTest {

    @Test
    void SCR16_cancelledAndExpired_shareOneTab() {
        assertThat(PlanTab.CANCELLED_EXPIRED.statuses())
                .containsExactlyInAnyOrder(PlanStatus.CANCELLED, PlanStatus.EXPIRED);
        assertThat(PlanTab.DRAFT.statuses()).containsExactly(PlanStatus.DRAFT);
    }

    @Test
    void SCR16_everyStatus_isOnExactlyOneTab() {
        Set<PlanStatus> covered = Arrays.stream(PlanTab.values())
                .flatMap(tab -> tab.statuses().stream())
                .collect(Collectors.toSet());
        long listed = Arrays.stream(PlanTab.values())
                .mapToLong(tab -> tab.statuses().size())
                .sum();

        assertThat(covered).isEqualTo(EnumSet.allOf(PlanStatus.class));
        assertThat(listed).isEqualTo(PlanStatus.values().length);
    }

    @Test
    void theStatusesOfATab_areACopy() {
        PlanTab.ACTIVE.statuses().add(PlanStatus.DRAFT);

        assertThat(PlanTab.ACTIVE.statuses()).containsExactly(PlanStatus.ACTIVE);
    }
}
