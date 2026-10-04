package com.cryptopilot.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.common.config.TrustedProxyProperties;
import com.cryptopilot.common.config.WebEdgeConfig;
import jakarta.servlet.ServletRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.servlet.filter.OrderedRequestContextFilter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The filter hands the rest of the request a {@code getRemoteAddr()} that is the resolved client, and runs before
 * anything that reads it.
 *
 * <p>Rule: NSF-18; TECHNICAL_DESIGN 5.3.
 */
class ClientAddressFilterTest {

    @Test
    void TD53_downstream_getRemoteAddr_isTheResolvedClient() throws Exception {
        MockHttpServletRequest request = request("10.0.0.5", "203.0.113.7");

        assertThat(remoteAddressSeenDownstream(List.of("10.0.0.5"), request)).isEqualTo("203.0.113.7");
    }

    @Test
    void TD53_aSpoofedHeaderFromAnUntrustedPeer_doesNotChangeTheAddress() throws Exception {
        MockHttpServletRequest request = request("198.51.100.20", "203.0.113.7");

        assertThat(remoteAddressSeenDownstream(List.of("10.0.0.5"), request)).isEqualTo("198.51.100.20");
    }

    @Test
    void TD53_withNoTrustedProxy_theRequestIsPassedOnUntouched() throws Exception {
        MockHttpServletRequest request = request("10.0.0.5", "203.0.113.7");
        AtomicReference<ServletRequest> seen = new AtomicReference<>();

        filter(List.of()).doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(req));

        assertThat(seen.get()).isSameAs(request);
    }

    /** Registered by configuration, not as a component, at its order: MVC test slices then load without it. */
    @Test
    void TD53_theFilter_isRegisteredByTheEdgeConfiguration_atItsOrder() {
        var registration = new WebEdgeConfig().clientAddressFilter(new TrustedProxyProperties(List.of("10.0.0.5")));

        assertThat(registration.getOrder()).isEqualTo(ClientAddressFilter.ORDER);
        assertThat(registration.getFilter()).isInstanceOf(ClientAddressFilter.class);
        assertThat(ClientAddressFilter.class.isAnnotationPresent(org.springframework.stereotype.Component.class))
                .isFalse();
    }

    /** Before Spring's request context (which the audit trail reads) and before the security chain (the limiter). */
    @Test
    void TD53_theFilter_runsBeforeTheRequestContextAndTheSecurityChain() {
        assertThat(ClientAddressFilter.ORDER)
                .isGreaterThan(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
                .isLessThan(new OrderedRequestContextFilter().getOrder())
                .isLessThan(SecurityFilterProperties.DEFAULT_FILTER_ORDER);
    }

    private static String remoteAddressSeenDownstream(List<String> trusted, MockHttpServletRequest request)
            throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter(trusted).doFilter(request, new MockHttpServletResponse(), chain);
        return chain.getRequest().getRemoteAddr();
    }

    private static ClientAddressFilter filter(List<String> trusted) {
        return new ClientAddressFilter(trusted);
    }

    private static MockHttpServletRequest request(String peer, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/market/pairs");
        request.setRemoteAddr(peer);
        request.addHeader(ClientAddressFilter.FORWARDED_FOR, forwardedFor);
        return request;
    }
}
