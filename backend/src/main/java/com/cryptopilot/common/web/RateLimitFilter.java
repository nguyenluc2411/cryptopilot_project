package com.cryptopilot.common.web;

import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ProblemDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies the request limits of ADR-014 and answers 429 with {@code Retry-After} and the standard problem body
 * ({@code RATE_LIMITED}, MSG50, the seconds to wait as its argument).
 *
 * <ul>
 *   <li>{@code POST /api/v1/auth/login}: rule {@code login}, per client address.
 *   <li>Any other {@code POST /api/v1/auth/**}: rule {@code auth}, per client address.
 *   <li>{@code POST /api/v1/paper/orders} by an authenticated account: rule {@code paper-order}, per account, in its
 *       own window and instead of {@code api}, as Binance counts new orders apart from its request weight.
 *   <li>Any other request under {@code /api/v1/} by an authenticated account: rule {@code api}, per account.
 * </ul>
 *
 * <p>Registered by {@code common.config.WebEdgeConfig} at {@link #ORDER}, just after Spring Security's chain and so
 * inside it: the account is known, and requests the chain refuses never reach the limiter. The client address is the
 * one {@link ClientAddressFilter} resolved.
 *
 * <p>Rule: TECHNICAL_DESIGN 5.3; ADR-014.
 *
 * <p>Reference: Nottingham, M. &amp; Fielding, R. (2012). RFC 6585: Additional HTTP Status Codes, section 4. IETF.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /** Just after Spring Security's chain (order -100). */
    public static final int ORDER = -99;

    static final String DETAIL = "Too many requests; try again after the time in Retry-After.";

    private static final String API = "/api/v1/";
    private static final String AUTH = "/api/v1/auth/";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String PAPER_ORDERS = "/api/v1/paper/orders";
    private static final String MESSAGE_ARGS = "messageArgs";

    /**
     * Requests allowed per window and subject, by rule.
     *
     * @param enabled false lets every request through (test contexts)
     */
    public record Limits(boolean enabled, int login, int auth, int api, int paperOrders) {}

    private final Limits limits;
    private final FixedWindowRateLimiter limiter;
    private final FixedWindowRateLimiter paperOrderLimiter;
    private final ObjectMapper json;

    /**
     * @param limiter counts the rules {@code login}, {@code auth} and {@code api}
     * @param paperOrderLimiter counts the rule {@code paper-order}, which has a window of its own
     */
    public RateLimitFilter(
            Limits limits,
            FixedWindowRateLimiter limiter,
            FixedWindowRateLimiter paperOrderLimiter,
            ObjectMapper json) {
        this.limits = limits;
        this.limiter = limiter;
        this.paperOrderLimiter = paperOrderLimiter;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !limits.enabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Optional<Limit> limit = limitOf(request);
        if (limit.isPresent()) {
            FixedWindowRateLimiter counter = limit.get().limiter();
            FixedWindowRateLimiter.Decision decision = counter.tryAcquire(
                    limit.get().rule(), limit.get().subject(), limit.get().perWindow());
            if (!decision.allowed()) {
                refuse(response, decision.retryAfterSeconds());
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private Optional<Limit> limitOf(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("POST".equals(request.getMethod()) && path.startsWith(AUTH)) {
            return LOGIN.equals(path)
                    ? Optional.of(new Limit("login", "ip:" + request.getRemoteAddr(), limits.login(), limiter))
                    : Optional.of(new Limit("auth", "ip:" + request.getRemoteAddr(), limits.auth(), limiter));
        }
        Authentication caller = SecurityContextHolder.getContext().getAuthentication();
        if (path.startsWith(API)
                && caller != null
                && caller.isAuthenticated()
                && !(caller instanceof AnonymousAuthenticationToken)) {
            if ("POST".equals(request.getMethod()) && PAPER_ORDERS.equals(path)) {
                return Optional.of(
                        new Limit("paper-order", "user:" + caller.getName(), limits.paperOrders(), paperOrderLimiter));
            }
            return Optional.of(new Limit("api", "user:" + caller.getName(), limits.api(), limiter));
        }
        return Optional.empty();
    }

    private void refuse(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        ProblemDetail body = ProblemDetails.of(
                ErrorCode.RATE_LIMITED,
                DETAIL,
                CorrelationIdFilter.currentCorrelationId().orElse(null));
        body.setProperty(MESSAGE_ARGS, List.of(Long.toString(retryAfterSeconds)));
        response.setStatus(ErrorCode.RATE_LIMITED.status().value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), body);
    }

    private record Limit(String rule, String subject, int perWindow, FixedWindowRateLimiter limiter) {}
}
