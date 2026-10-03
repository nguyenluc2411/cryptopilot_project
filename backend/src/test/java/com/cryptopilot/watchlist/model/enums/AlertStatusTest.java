package com.cryptopilot.watchlist.model.enums;

import static com.cryptopilot.watchlist.model.enums.AlertStatus.ACTIVE;
import static com.cryptopilot.watchlist.model.enums.AlertStatus.EXPIRED;
import static com.cryptopilot.watchlist.model.enums.AlertStatus.PAUSED;
import static com.cryptopilot.watchlist.model.enums.AlertStatus.TRIGGERED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class AlertStatusTest {

    @ParameterizedTest
    @CsvSource({
        "ACTIVE,PAUSED,true",
        "ACTIVE,TRIGGERED,true",
        "ACTIVE,EXPIRED,true",
        "ACTIVE,ACTIVE,false",
        "PAUSED,ACTIVE,true",
        "PAUSED,EXPIRED,true",
        "PAUSED,TRIGGERED,false",
        "PAUSED,PAUSED,false",
        "TRIGGERED,ACTIVE,true",
        "TRIGGERED,PAUSED,false",
        "TRIGGERED,EXPIRED,false",
        "TRIGGERED,TRIGGERED,false",
        "EXPIRED,ACTIVE,true",
        "EXPIRED,PAUSED,false",
        "EXPIRED,TRIGGERED,false",
        "EXPIRED,EXPIRED,false"
    })
    void SRS343_theTransitionTable_listsExactlyTheseMoves(AlertStatus from, AlertStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @EnumSource(AlertStatus.class)
    void SRS343_onlyTriggeredAndExpiredAlerts_comeBackByAnEdit(AlertStatus status) {
        assertThat(status.reactivatesOnEdit()).isEqualTo(status == TRIGGERED || status == EXPIRED);
        assertThat(ACTIVE.reactivatesOnEdit() || PAUSED.reactivatesOnEdit()).isFalse();
    }
}
