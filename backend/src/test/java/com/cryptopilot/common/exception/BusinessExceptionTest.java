package com.cryptopilot.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BusinessExceptionTest {

    @Test
    void exception_carriesTheErrorCodeAndTheDetail() {
        BusinessException exception =
                new BusinessException(ErrorCode.VALIDATION_FAILED, "Leverage 125 exceeds the maximum of the pair");

        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(exception.getMessage()).isEqualTo("Leverage 125 exceeds the maximum of the pair");
        assertThat(exception.getCause()).isNull();
    }

    @Test
    void exception_keepsTheTechnicalCauseForTheLog() {
        IllegalStateException cause = new IllegalStateException("the pair was disabled mid-request");

        BusinessException exception =
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "The pair is no longer available", cause);

        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    void missingErrorCode_isRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new BusinessException(null, "detail"))
                .withMessageContaining("errorCode must not be null");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void blankDetail_isRejected(String detail) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, detail))
                .withMessageContaining("detail must not be blank");
    }

    @Test
    void exception_isUnchecked_soAUseCaseNeedsNoThrowsClause() {
        assertThat(RuntimeException.class).isAssignableFrom(BusinessException.class);
    }
}
