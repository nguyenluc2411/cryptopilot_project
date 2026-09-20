package com.cryptopilot.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ResourceNotFoundExceptionTest {

    @Test
    void exception_mapsToTheNotFoundCode() {
        UUID id = UUID.fromString("0199a1e0-0000-7000-8000-000000000001");

        ResourceNotFoundException exception = new ResourceNotFoundException("TradingPlan", id);

        assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(exception.getMessage()).isEqualTo("TradingPlan " + id + " was not found");
    }

    @Test
    void exception_isABusinessException_soOneHandlerCoversBoth() {
        assertThat(BusinessException.class).isAssignableFrom(ResourceNotFoundException.class);
    }

    @Test
    void missingIdentifier_stillProducesAReadableDetail() {
        assertThat(new ResourceNotFoundException("Watchlist", null).getMessage())
                .isEqualTo("Watchlist null was not found");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void blankResourceName_isRejected(String resource) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ResourceNotFoundException(resource, 1L))
                .withMessageContaining("resource must not be blank");
    }
}
