package com.cryptopilot.common.web;

import com.cryptopilot.common.config.TrustedProxyProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes {@code getRemoteAddr()} answer the client's address for the rest of the request, resolved once by
 * {@link ClientAddressResolver}. Everything that reads the client address — the audit trail, the rate limiter — reads
 * it from the request and so gets the same answer.
 *
 * <p>Ordered right after {@link CorrelationIdFilter}, before Spring's request-context filter and the security chain.
 * {@code server.forward-headers-strategy} stays {@code none}, so the container does not apply a second, differently
 * configured resolution.
 *
 * <p>Rule: NSF-18; TECHNICAL_DESIGN 5.3.
 *
 * <p>Reference: Petersson, A. &amp; Nilsson, M. (2014). RFC 7239: Forwarded HTTP Extension, section 8. IETF.
 */
@Component
@Order(ClientAddressFilter.ORDER)
public class ClientAddressFilter extends OncePerRequestFilter {

    /** After the correlation id, before every Spring filter that reads the request. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    static final String FORWARDED_FOR = "X-Forwarded-For";

    private final ClientAddressResolver resolver;

    public ClientAddressFilter(TrustedProxyProperties properties) {
        this.resolver = new ClientAddressResolver(properties.trustedProxies());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (resolver.trustsNoProxy()) {
            filterChain.doFilter(request, response);
            return;
        }
        List<String> forwardedFor = Collections.list(request.getHeaders(FORWARDED_FOR));
        String client = resolver.resolve(request.getRemoteAddr(), forwardedFor);
        if (Objects.equals(client, request.getRemoteAddr())) {
            filterChain.doFilter(request, response);
            return;
        }
        filterChain.doFilter(new ClientAddressRequest(request, client), response);
    }

    private static final class ClientAddressRequest extends HttpServletRequestWrapper {

        private final String client;

        ClientAddressRequest(HttpServletRequest request, String client) {
            super(request);
            this.client = client;
        }

        @Override
        public String getRemoteAddr() {
            return client;
        }

        @Override
        public String getRemoteHost() {
            return client;
        }
    }
}
