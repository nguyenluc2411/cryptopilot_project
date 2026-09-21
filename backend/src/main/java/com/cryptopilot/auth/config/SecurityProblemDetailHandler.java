package com.cryptopilot.auth.config;

import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ProblemDetails;
import com.cryptopilot.common.web.CorrelationIdFilter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Gives a request refused by the filter chain the same response body as a request refused by a
 * business rule.
 *
 * <h2>Why the advice cannot do this</h2>
 *
 * <p>{@code GlobalExceptionHandler} answers exceptions that reach the web boundary. A request the
 * security chain refuses never reaches it: the refusal happens in a filter, before the dispatcher
 * chooses a handler, so there is no controller invocation for an {@code @ExceptionHandler} to wrap.
 * Spring Security's own defaults then write the status and an empty body. That leaves a client
 * parsing two shapes for one API - a problem detail everywhere else, and nothing at all for the two
 * outcomes it is most likely to meet - so the body is written here instead, from the same
 * {@link ProblemDetails} factory the advice uses.
 *
 * <h2>The standard headers are kept by delegating for them</h2>
 *
 * <p>Both defaults are called first and then written over, rather than replaced. They set the status
 * and, more importantly, the {@code WWW-Authenticate} header that RFC 6750 requires of a bearer
 * resource server - naming the scheme on a 401, and on a 403 the error that applies. Reimplementing
 * that header would mean maintaining a second reading of the specification; calling the class that
 * already implements it means this one only adds the body. Neither default writes or commits a body,
 * which is what makes appending one safe.
 *
 * <h2>What the two answers say, and what they withhold</h2>
 *
 * <p>The 401 says a usable credential was not presented, without distinguishing absent from expired
 * from wrongly signed. The 403 says the caller's role does not reach the endpoint, without naming
 * the role that would. Both sentences are fixed text: nothing about the request, the token or the
 * matched rule is echoed back, so neither can be used to map the application by probing it.
 *
 * <p>Rule: SRS 3.1.3, SRS 4.2.4; TECHNICAL_DESIGN sections 5.1 and 5.3; messages MSG43, MSG44.
 *
 * <p>Reference: Jones, M. &amp; Hardt, D. (2012). RFC 6750, <i>The OAuth 2.0 Authorization Framework:
 * Bearer Token Usage</i>, section 3 (the {@code WWW-Authenticate} response header field).
 * <p>Reference: Nottingham, M., Wilde, E. &amp; Dalal, S. (2023). RFC 9457, <i>Problem Details for
 * HTTP APIs</i>.
 */
@Component
public class SecurityProblemDetailHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    /** Text of a 401. Says that a credential is needed, and nothing about the one that was sent. */
    static final String UNAUTHENTICATED_DETAIL = "Authentication is required to access this resource.";

    /** Text of a 403. Says the caller may not, and not what would make them may. */
    static final String FORBIDDEN_DETAIL = "You are not allowed to access this resource.";

    private final AuthenticationEntryPoint challenge = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler refusal = new BearerTokenAccessDeniedHandler();
    private final ObjectMapper objectMapper;

    SecurityProblemDetailHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** No usable credential was presented (SRS 4.2.4). */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException, ServletException {
        challenge.commence(request, response, exception);
        write(response, ErrorCode.AUTHENTICATION_REQUIRED, UNAUTHENTICATED_DETAIL);
    }

    /** The caller is authenticated and their role does not reach this endpoint (SRS 3.1.3). */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException, ServletException {
        refusal.handle(request, response, exception);
        write(response, ErrorCode.ACCESS_DENIED, FORBIDDEN_DETAIL);
    }

    /**
     * Writes the body over the status and headers the delegate has already set.
     *
     * <p>A committed response is left alone. That happens when something had already begun writing
     * before the refusal - appending a second body would corrupt the first, and the status is out by
     * then in any case.
     */
    private void write(HttpServletResponse response, ErrorCode errorCode, String detail) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        ProblemDetail body = ProblemDetails.of(
                errorCode, detail, CorrelationIdFilter.currentCorrelationId().orElse(null));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
