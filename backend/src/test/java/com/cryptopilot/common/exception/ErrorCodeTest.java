package com.cryptopilot.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Guards the catalogue itself rather than any one entry: whatever constants later tasks add, each
 * one must still name exactly one message of SRS section 5.3 and must still be distinguishable from
 * the others. The checks iterate the enum, so a constant added without a message code fails here
 * instead of reaching a client as an error nobody can display.
 */
class ErrorCodeTest {

    /** Ids of SRS section 5.3 run from MSG01 to MSG45. */
    private static final Pattern SRS_MESSAGE_ID = Pattern.compile("MSG\\d{2}");

    private static final int HIGHEST_SRS_MESSAGE = 45;

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyCode_namesOneExistingSrsMessage(ErrorCode errorCode) {
        assertThat(errorCode.messageCode()).matches(SRS_MESSAGE_ID);
        assertThat(Integer.parseInt(errorCode.messageCode().substring(3))).isBetween(1, HIGHEST_SRS_MESSAGE);
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyCode_carriesAnErrorStatus(ErrorCode errorCode) {
        assertThat(errorCode.status().isError())
                .as("%s must answer with a 4xx or 5xx status", errorCode)
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyCode_isItsOwnStableIdentifier(ErrorCode errorCode) {
        assertThat(errorCode.code()).isEqualTo(errorCode.name());
    }

    @Test
    void codes_areUnique() {
        assertThat(Arrays.stream(ErrorCode.values())
                        .map(ErrorCode::code)
                        .distinct()
                        .count())
                .isEqualTo(ErrorCode.values().length);
    }

    @Test
    void theKernelCodes_mapToTheMessagesTheDesignAssignsThem() {
        assertThat(ErrorCode.VALIDATION_FAILED.messageCode()).isEqualTo("MSG01");
        assertThat(ErrorCode.VALIDATION_FAILED.status().value()).isEqualTo(400);

        assertThat(ErrorCode.RESOURCE_NOT_FOUND.messageCode()).isEqualTo("MSG41");
        assertThat(ErrorCode.RESOURCE_NOT_FOUND.status().value()).isEqualTo(404);

        assertThat(ErrorCode.INTERNAL_ERROR.messageCode()).isEqualTo("MSG43");
        assertThat(ErrorCode.INTERNAL_ERROR.status().value()).isEqualTo(500);
    }
}
