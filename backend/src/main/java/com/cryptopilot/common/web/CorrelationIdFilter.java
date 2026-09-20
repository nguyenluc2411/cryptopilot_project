package com.cryptopilot.common.web;

import com.cryptopilot.common.util.UuidV7;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id, puts it in the logging context for the duration of the
 * request and echoes it back in the response.
 *
 * <p>One user action crosses the web layer, a queue consumer and an outbound call, and the log
 * lines of concurrent requests interleave. The correlation id is what makes those lines one story
 * again, and it is the reference code a user reads back from an unexpected error (MSG43).
 *
 * <p>An id offered by the caller is kept, so that a trace started in the web client or in a gateway
 * continues here, but only if it is short and made of harmless characters. An arbitrary header
 * value would otherwise reach the log file and the response header, where it could forge log lines
 * or inject control characters.
 *
 * <p>The entry is removed in a {@code finally} block: the thread that served this request serves
 * the next one, and a stale id is worse than no id.
 *
 * <p>Rule: TECHNICAL_DESIGN sections 1.3 (correlation id in MDC) and 5.1 ({@code traceId}).
 *
 * <p>Reference: Nygard, M. (2018). <i>Release It!</i> (2nd ed.). Pragmatic Bookshelf, ch. 8
 * (transparency: correlation ids tie the records of one request together).
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Request and response header carrying the id. */
    public static final String HEADER_NAME = "X-Correlation-Id";

    /** Key the id is published under in the logging context. */
    public static final String MDC_KEY = "correlationId";

    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = resolve(request.getHeader(HEADER_NAME));
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * The correlation id of the request being served on this thread, or empty when there is none,
     * for example on a background worker.
     */
    public static Optional<String> currentCorrelationId() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }

    /** Keeps an acceptable id offered by the caller; generates one otherwise. */
    static String resolve(String offered) {
        return offered != null && ACCEPTED.matcher(offered).matches()
                ? offered
                : UuidV7.next().toString();
    }
}
