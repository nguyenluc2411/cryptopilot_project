package com.cryptopilot.trading.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A rejection reported as the {@code errors} of MSG01: one entry per field, the first violation of each. */
class RiskInputRejectedTest {

    @Test
    void MSG01_eachField_carriesTheMessageCodeOfItsFirstViolationInTheOrderReported() {
        RiskInputRejected rejected = new RiskInputRejected(List.of(
                new InputViolation("stopLoss", "MSG16", "wrong side"),
                new InputViolation("leverage", "MSG01", "Spot has no leverage"),
                new InputViolation("stopLoss", "MSG15", "not a tick multiple")));

        assertThat(rejected.fieldErrors())
                .containsExactly(Map.entry("stopLoss", "MSG16"), Map.entry("leverage", "MSG01"));
    }
}
