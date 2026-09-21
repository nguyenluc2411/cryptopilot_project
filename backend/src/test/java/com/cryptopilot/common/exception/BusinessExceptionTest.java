package com.cryptopilot.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // ------------------------------------------------------------------ message arguments

    @Test
    void anExceptionWithNoArguments_carriesAnEmptyList() {
        assertThat(new BusinessException(ErrorCode.VALIDATION_FAILED, "nothing to substitute").messageArgs())
                .isEmpty();
    }

    /** MSG09's minutes and MSG10's status: the values the client substitutes, in order. */
    @Test
    void anExceptionWithArguments_carriesThemInOrderAsText() {
        BusinessException refusal = new BusinessException(ErrorCode.LOGIN_TEMPORARILY_LOCKED, "locked out", 15L, "x");

        assertThat(refusal.messageArgs()).containsExactly("15", "x");
    }

    /**
     * A single {@link Throwable} selects the cause constructor and not the varargs one, which is both
     * what Java's overload resolution does and what a caller passing a cause means. Pinned, because
     * the day it resolved the other way a stack trace would be rendered into a response body.
     */
    @Test
    void aSingleThrowable_isACauseAndNotAMessageArgument() {
        IllegalStateException cause = new IllegalStateException("underneath");

        BusinessException refusal = new BusinessException(ErrorCode.DATA_CONFLICT, "refused", cause);

        assertThat(refusal.getCause()).isSameAs(cause);
        assertThat(refusal.messageArgs()).isEmpty();
    }

    @Test
    void theArgumentList_cannotBeChangedByItsReader() {
        BusinessException refusal = new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE, "banned", "BANNED");

        assertThatThrownBy(() -> refusal.messageArgs().add("LOCKED")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aNullArgument_isRefusedWhereItIsPassed() {
        assertThatThrownBy(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE, "banned", new Object[] {null}))
                .isInstanceOf(NullPointerException.class);
    }
}
