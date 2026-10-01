package com.cryptopilot.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldValidationExceptionTest {

    @Test
    void itIsAValidationFailure_namingEachFieldInTheOrderGiven() {
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put("stopLoss", "MSG16");
        errors.put("quantity", "MSG15");

        FieldValidationException exception = new FieldValidationException("rejected", errors);

        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(exception.getMessage()).isEqualTo("rejected");
        assertThat(exception.errors()).containsExactly(Map.entry("stopLoss", "MSG16"), Map.entry("quantity", "MSG15"));
    }

    @Test
    void theErrors_areACopyThatCallersCannotChange() {
        Map<String, String> errors = new LinkedHashMap<>(Map.of("stopLoss", "MSG16"));
        FieldValidationException exception = new FieldValidationException("rejected", errors);

        errors.put("takeProfit", "MSG16");
        exception.errors().put("leverage", "MSG01");

        assertThat(exception.errors()).containsOnlyKeys("stopLoss");
    }

    @Test
    void aValidationWithoutAField_isRefused() {
        assertThatThrownBy(() -> new FieldValidationException("rejected", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
