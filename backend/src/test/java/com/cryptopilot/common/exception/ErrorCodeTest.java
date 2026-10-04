package com.cryptopilot.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
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

    /**
     * Every constant with the SRS 5.3 message and the status reviewed for it. A constant added without a row here
     * fails, so no message id reaches a client unless someone checked it exists in SRS 5.3; ids no constant uses yet
     * (MSG46, MSG47, MSG49 among them) are not listed.
     */
    private static final Map<ErrorCode, String> REVIEWED = Map.ofEntries(
            Map.entry(ErrorCode.VALIDATION_FAILED, "MSG01 400"),
            Map.entry(ErrorCode.RESOURCE_NOT_FOUND, "MSG41 404"),
            Map.entry(ErrorCode.INTERNAL_ERROR, "MSG43 500"),
            Map.entry(ErrorCode.INVALID_CREDENTIALS, "MSG08 401"),
            Map.entry(ErrorCode.LOGIN_TEMPORARILY_LOCKED, "MSG09 429"),
            Map.entry(ErrorCode.ACCOUNT_NOT_ACTIVE, "MSG10 403"),
            Map.entry(ErrorCode.SESSION_EXPIRED, "MSG44 401"),
            Map.entry(ErrorCode.EMAIL_NOT_VERIFIED, "MSG11 403"),
            Map.entry(ErrorCode.EMAIL_ALREADY_VERIFIED, "MSG07 409"),
            Map.entry(ErrorCode.ACCOUNT_STATUS_TRANSITION_INVALID, "MSG39 409"),
            Map.entry(ErrorCode.TOKEN_INVALID_OR_EXPIRED, "MSG07 400"),
            Map.entry(ErrorCode.EMAIL_ALREADY_REGISTERED, "MSG04 409"),
            Map.entry(ErrorCode.PASSWORD_POLICY_VIOLATION, "MSG03 400"),
            Map.entry(ErrorCode.DATA_CONFLICT, "MSG43 409"),
            Map.entry(ErrorCode.CURRENT_PASSWORD_INCORRECT, "MSG08 400"),
            Map.entry(ErrorCode.AUTHENTICATION_REQUIRED, "MSG44 401"),
            Map.entry(ErrorCode.ACCESS_DENIED, "MSG43 403"),
            Map.entry(ErrorCode.PLAN_FEATURE_NOT_INCLUDED, "MSG29 403"),
            Map.entry(ErrorCode.PLAN_LIMIT_REACHED, "MSG27 409"),
            Map.entry(ErrorCode.AI_DAILY_QUOTA_EXHAUSTED, "MSG30 429"),
            Map.entry(ErrorCode.RATE_LIMITED, "MSG50 429"),
            Map.entry(ErrorCode.RISK_PROFILE_CONFIRMATION_REQUIRED, "MSG48 400"),
            Map.entry(ErrorCode.TRADING_BLOCKING_WARNING, "MSG18 409"),
            Map.entry(ErrorCode.TRADING_PLAN_STATUS_TRANSITION_INVALID, "MSG43 409"),
            Map.entry(ErrorCode.MARKET_PRICE_UNAVAILABLE, "MSG43 503"),
            Map.entry(ErrorCode.WATCHLIST_PAIR_ALREADY_WATCHED, "MSG45 409"),
            Map.entry(ErrorCode.WATCHLIST_REMOVAL_CONFIRMATION_REQUIRED, "MSG26 400"),
            Map.entry(ErrorCode.ALERT_STATUS_TRANSITION_INVALID, "MSG43 409"));

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyCode_carriesTheMessageAndStatusReviewedForIt(ErrorCode errorCode) {
        assertThat(REVIEWED).as("%s has no reviewed row", errorCode).containsKey(errorCode);
        assertThat(errorCode.messageCode() + " " + errorCode.status().value()).isEqualTo(REVIEWED.get(errorCode));
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

    /**
     * The two codes the security filter chain returns.
     *
     * <p>Worth pinning separately because neither message was chosen, both were settled for. MSG44
     * is right for a caller who must sign in again and is shared with {@code SESSION_EXPIRED} on
     * purpose. MSG43 is a borrowed reference-code text, because SRS section 5.3 has no message for a
     * refused authorization at all; the alignment item asking for one is open, and when it is
     * answered this assertion is where the new id lands.
     */
    @Test
    void SRS313_theSecurityCodes_carryTheStatusesTheMatrixDependsOn() {
        assertThat(ErrorCode.AUTHENTICATION_REQUIRED.status().value())
                .as("no usable credential")
                .isEqualTo(401);
        assertThat(ErrorCode.AUTHENTICATION_REQUIRED.messageCode()).isEqualTo("MSG44");

        assertThat(ErrorCode.ACCESS_DENIED.status().value())
                .as("authenticated, and the role does not reach the endpoint")
                .isEqualTo(403);
        assertThat(ErrorCode.ACCESS_DENIED.messageCode())
                .as("borrowed while SRS 5.3 has no message for a refused authorization")
                .isEqualTo("MSG43");
    }
}
