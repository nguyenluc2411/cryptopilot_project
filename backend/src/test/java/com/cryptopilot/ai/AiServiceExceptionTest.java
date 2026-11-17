package com.cryptopilot.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Which AI Service failures count as an answer of the service, and which stop a run. */
class AiServiceExceptionTest {

    private static AiServiceException failure(String code, boolean retryable, int status) {
        return new AiServiceException(code, retryable, "x", null, status);
    }

    @Test
    void aClientErrorThatIsNotRetried_isTheServicesAnswer() {
        assertThat(failure("INVALID_REQUEST", false, 422).serviceAnswered()).isTrue();
    }

    @Test
    void authorisationErrors_retriedErrors_serverErrors_andABadAnswer_areNotTheServicesAnswer() {
        assertThat(failure("X", false, 401).serviceAnswered()).isFalse();
        assertThat(failure("X", false, 403).serviceAnswered()).isFalse();
        assertThat(failure("X", true, 429).serviceAnswered()).isFalse();
        assertThat(failure("X", false, 500).serviceAnswered()).isFalse();
        assertThat(failure("X", false, 0).serviceAnswered()).isFalse();
        assertThat(failure(AiServiceException.BAD_ANSWER, false, 422).serviceAnswered())
                .isFalse();
    }

    @Test
    void anUnconfiguredUnreachableOrRateLimitedService_stopsTheRun_andOtherFailuresDoNot() {
        assertThat(failure(AiServiceException.NOT_CONFIGURED, false, 0).stopsTheRun())
                .isTrue();
        assertThat(failure(AiServiceException.UNREACHABLE, true, 0).stopsTheRun())
                .isTrue();
        assertThat(failure(AiServiceException.RATE_LIMITED, true, 429).stopsTheRun())
                .isTrue();
        assertThat(failure(AiServiceException.BAD_ANSWER, false, 200).stopsTheRun())
                .isFalse();
        assertThat(failure("LLM_REJECTED", false, 502).stopsTheRun()).isFalse();
    }

    @Test
    void NSF11_anOverloadedOrSlowProvider_stopsTheRun_andOnlyATimeoutMayBeTheRequest() {
        assertThat(failure(AiServiceException.LLM_UNAVAILABLE, true, 503).stopsTheRun())
                .isTrue();
        assertThat(failure(AiServiceException.LLM_TIMEOUT, true, 504).stopsTheRun())
                .isTrue();
        assertThat(failure(AiServiceException.LLM_TIMEOUT, true, 504).mayBeTheRequest())
                .isTrue();
        assertThat(failure(AiServiceException.LLM_UNAVAILABLE, true, 503).mayBeTheRequest())
                .isFalse();
        assertThat(failure(AiServiceException.RATE_LIMITED, true, 429).mayBeTheRequest())
                .isFalse();
        assertThat(failure(AiServiceException.UNREACHABLE, true, 0).mayBeTheRequest())
                .isFalse();
    }
}
