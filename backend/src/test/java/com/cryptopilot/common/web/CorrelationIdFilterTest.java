package com.cryptopilot.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearLoggingContext() {
        MDC.clear();
    }

    @Test
    void requestWithoutAHeader_getsAGeneratedIdentifier() throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenDuringTheRequest = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest(), response, capturingChain(seenDuringTheRequest));

        assertThat(seenDuringTheRequest.get()).isNotNull();
        assertThat(UUID.fromString(seenDuringTheRequest.get()).version()).isEqualTo(7);
        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo(seenDuringTheRequest.get());
    }

    @Test
    void requestWithAnAcceptableHeader_keepsTheCallerIdentifier() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "web-7f3a_2b.1:9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenDuringTheRequest = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(seenDuringTheRequest));

        assertThat(seenDuringTheRequest.get()).isEqualTo("web-7f3a_2b.1:9");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo("web-7f3a_2b.1:9");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                " ",
                "has spaces",
                "line\nbreak",
                "semi;colon",
                "0123456789012345678901234567890123456789012345678901234567890123456789"
            })
    void unusableHeader_isReplacedInsteadOfReachingTheLog(String offered) {
        String resolved = CorrelationIdFilter.resolve(offered);

        assertThat(resolved).isNotEqualTo(offered);
        assertThat(UUID.fromString(resolved).version()).isEqualTo(7);
    }

    @Test
    void identifier_isRemovedAfterTheRequest_soTheNextRequestOnThisThreadStartsClean()
            throws ServletException, IOException {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
        assertThat(CorrelationIdFilter.currentCorrelationId()).isEmpty();
    }

    @Test
    void identifier_isRemovedEvenWhenTheRequestFails() {
        MockFilterChain failing = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                throw new IllegalStateException("the handler blew up");
            }
        };

        try {
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failing);
        } catch (IllegalStateException | ServletException | IOException expected) {
            // The point of the test is what happens to the logging context, not the exception.
        }

        assertThat(CorrelationIdFilter.currentCorrelationId()).isEmpty();
    }

    @Test
    void currentCorrelationId_isTheOneOfTheRequestBeingServed() throws ServletException, IOException {
        AtomicReference<String> seenDuringTheRequest = new AtomicReference<>();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "trace-42");

        filter.doFilter(request, new MockHttpServletResponse(), capturingChain(seenDuringTheRequest));

        assertThat(seenDuringTheRequest.get()).isEqualTo("trace-42");
    }

    /** A chain that records what {@code currentCorrelationId()} answers while the request runs. */
    private static MockFilterChain capturingChain(AtomicReference<String> target) {
        return new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                target.set(CorrelationIdFilter.currentCorrelationId().orElse(null));
            }
        };
    }
}
