package com.cryptopilot.trading.model.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** The transition table of BR-32, every one of the 25 pairs. */
class PlanStatusTest {

    private static final Set<String> ALLOWED =
            Set.of("DRAFT>ACTIVE", "DRAFT>CANCELLED", "ACTIVE>EXECUTED", "ACTIVE>CANCELLED", "ACTIVE>EXPIRED");

    static Stream<Arguments> everyPair() {
        return Arrays.stream(PlanStatus.values())
                .flatMap(from -> Arrays.stream(PlanStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void BR32_onlyTheListedTransitionsAreAllowed(PlanStatus from, PlanStatus to) {
        assertThat(from.canTransitionTo(to)).isEqualTo(ALLOWED.contains(from + ">" + to));
    }

    @ParameterizedTest
    @EnumSource(
            value = PlanStatus.class,
            names = {"EXECUTED", "CANCELLED", "EXPIRED"})
    void BR32_theEndStatuses_areTerminalAndAllowNothing(PlanStatus status) {
        assertThat(status.isTerminal()).isTrue();
        assertThat(EnumSet.allOf(PlanStatus.class)).noneMatch(status::canTransitionTo);
    }

    @Test
    void BR32_draftAndActive_areNotTerminal() {
        assertThat(PlanStatus.DRAFT.isTerminal()).isFalse();
        assertThat(PlanStatus.ACTIVE.isTerminal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(PlanStatus.class)
    void BR32_noStatus_movesToItself(PlanStatus status) {
        assertThat(status.canTransitionTo(status)).isFalse();
    }
}
