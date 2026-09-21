package com.cryptopilot.common.exception;

import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.common.web.CorrelationIdFilter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception that reaches the web boundary into one response shape, so that a client
 * parses errors the same way whichever endpoint produced them.
 *
 * <p>The shape is the problem detail of RFC 9457, produced by Spring rather than hand-rolled, with
 * three properties added: {@code code}, the stable identifier a client branches on;
 * {@code messageCode}, the SRS message whose text the client displays; and {@code traceId}, the
 * correlation id that ties the response to the log lines of the same request. A fourth,
 * {@code messageArgs}, appears only when the message is a sentence with a hole in it - MSG09's
 * minutes, MSG10's status - and carries the values in the order the placeholders appear.
 *
 * <p>An unhandled exception is the one case where the response says less than the handler knows.
 * The stack trace and the message go to the log with a reference code; the caller receives that
 * reference and nothing else, because an exception message is exactly the kind of internal detail
 * an attacker probes for (SRS 4.2.4, MSG43).
 *
 * <p>Rule: TECHNICAL_DESIGN section 5.1; SRS 4.2.4; messages MSG01, MSG41, MSG43.
 *
 * <p>Reference: Nottingham, M., Wilde, E. and Dalal, S. (2023). RFC 9457, <i>Problem Details for
 * HTTP APIs</i>.
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>.
 * Addison-Wesley (exception translation at a layer boundary).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String CODE = "code";
    private static final String MESSAGE_CODE = "messageCode";
    private static final String TRACE_ID = "traceId";
    private static final String ERRORS = "errors";
    private static final String MESSAGE_ARGS = "messageArgs";

    /** Text of an unexpected failure; deliberately says nothing about the cause. */
    static final String UNEXPECTED_DETAIL = "An unexpected error occurred.";

    /** Text of a refused write; names no table and no constraint. */
    static final String CONFLICT_DETAIL = "The request conflicts with data that already exists.";

    /** Message of a field error whose validation constraint declares none. */
    static final String DEFAULT_FIELD_MESSAGE = "is invalid";

    /** A request refused by a business rule: the error code decides the status. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.errorCode();
        log.info("Request refused: code={} detail={}", errorCode.code(), exception.getMessage());
        ProblemDetail body = problemDetail(errorCode, exception.getMessage(), traceId());
        if (!exception.messageArgs().isEmpty()) {
            body.setProperty(MESSAGE_ARGS, exception.messageArgs());
        }
        return ResponseEntity.status(errorCode.status()).body(body);
    }

    /**
     * A write the database refused because it would have duplicated a row, or otherwise broken an
     * integrity constraint.
     *
     * <p>Without this, a lost race — two registrations of the same address arriving together, one
     * of them committing first — would reach the client as an unhandled exception and a 500, which
     * says the server is broken when in fact the rule worked. The constraint name goes to the log,
     * where it identifies which rule fired; the response carries the correlation id and nothing
     * about the schema, since a constraint name tells a caller what the tables are called.
     *
     * <p>A use case that expects a particular collision does not rely on this: it catches the
     * violation and raises the {@link ErrorCode} whose message SRS 5.3 assigns to that rule. This
     * handler is what keeps the unanticipated one from looking like a crash.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException exception) {
        String reference = CorrelationIdFilter.currentCorrelationId()
                .orElseGet(() -> UuidV7.next().toString());
        log.warn("Integrity constraint refused a write, reference {}: {}", reference, mostSpecificMessage(exception));
        ProblemDetail body = problemDetail(ErrorCode.DATA_CONFLICT, CONFLICT_DETAIL, reference);
        return ResponseEntity.status(ErrorCode.DATA_CONFLICT.status()).body(body);
    }

    /** Anything the application did not anticipate: logged in full, answered with a reference. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpectedException(Exception exception) {
        String reference = CorrelationIdFilter.currentCorrelationId()
                .orElseGet(() -> UuidV7.next().toString());
        log.error("Unhandled exception, reference {}", reference, exception);
        ProblemDetail body = problemDetail(ErrorCode.INTERNAL_ERROR, UNEXPECTED_DETAIL, reference);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status()).body(body);
    }

    /**
     * A request body that failed Bean Validation. The offending fields are reported one by one
     * under {@code errors}, so the web client can show each message under its own input instead of
     * one banner for the whole form.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fieldError.getField(), messageOf(fieldError));
        }
        for (ObjectError globalError : exception.getBindingResult().getGlobalErrors()) {
            errors.putIfAbsent(globalError.getObjectName(), messageOf(globalError));
        }
        ProblemDetail body = problemDetail(ErrorCode.VALIDATION_FAILED, "Request validation failed.", traceId());
        body.setProperty(ERRORS, errors);
        return handleExceptionInternal(exception, body, headers, status, request);
    }

    private static ProblemDetail problemDetail(ErrorCode errorCode, String detail, String traceId) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(errorCode.status(), detail);
        body.setTitle(errorCode.status().getReasonPhrase());
        body.setProperty(CODE, errorCode.code());
        body.setProperty(MESSAGE_CODE, errorCode.messageCode());
        if (traceId != null) {
            body.setProperty(TRACE_ID, traceId);
        }
        return body;
    }

    private static String messageOf(ObjectError error) {
        String message = error.getDefaultMessage();
        return message == null || message.isBlank() ? DEFAULT_FIELD_MESSAGE : message;
    }

    private static String traceId() {
        return CorrelationIdFilter.currentCorrelationId().orElse(null);
    }

    /** The innermost message, which is where the driver puts the name of the constraint. */
    private static String mostSpecificMessage(Throwable exception) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
