package com.cryptopilot.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.common.web.CorrelationIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.io.UnsupportedEncodingException;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * Drives the handler through the real web stack, because half of what is asserted here is
 * behaviour the framework contributes: the status, the {@code application/problem+json} content
 * type and the shape of the body.
 *
 * <p>A slice is used rather than a full application context: the handler needs a dispatcher and a
 * controller that throws, and nothing else.
 *
 * <p>The controller below exists only for this test. It is the smallest endpoint that can raise
 * each of the cases the handler distinguishes.
 */
@WebMvcTest
@Import(GlobalExceptionHandlerTest.ThrowingController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvcTester mockMvc;

    @Test
    void businessException_answersWithTheStatusAndCodesOfItsErrorCode() {
        MvcTestResult result = mockMvc.get().uri("/test-errors/business").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.messageCode").isEqualTo("MSG01");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(400);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Leverage 125 exceeds the maximum");
    }

    @Test
    void resourceNotFound_answers404WithMsg41() {
        MvcTestResult result = mockMvc.get().uri("/test-errors/missing").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(result).bodyJson().extractingPath("$.messageCode").isEqualTo("MSG41");
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("TradingPlan 42 was not found");
    }

    @Test
    void everyProblemDetail_carriesTheCorrelationIdOfTheRequest() {
        MvcTestResult result = mockMvc.get()
                .uri("/test-errors/missing")
                .header(CorrelationIdFilter.HEADER_NAME, "trace-123")
                .exchange();

        assertThat(result).bodyJson().extractingPath("$.traceId").isEqualTo("trace-123");
    }

    @Test
    void unknownException_answers500AndLeaksNothingAboutTheCause() throws UnsupportedEncodingException {
        MvcTestResult result = mockMvc.get().uri("/test-errors/broken").exchange();

        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("connection string")
                .doesNotContain("secret")
                .doesNotContain("IllegalStateException")
                .doesNotContain("com.cryptopilot");
    }

    @Test
    void unknownException_stillGivesTheUserAReferenceCodeForMsg43() {
        MvcTestResult result = mockMvc.get().uri("/test-errors/broken").exchange();

        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INTERNAL_ERROR");
        assertThat(result).bodyJson().extractingPath("$.messageCode").isEqualTo("MSG43");
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo(GlobalExceptionHandler.UNEXPECTED_DETAIL);
        assertThat(result).bodyJson().extractingPath("$.traceId").asString().isNotBlank();
    }

    /**
     * A write the database refused. It is a conflict, not a crash: the rule worked, and the caller
     * is told so with a 409 rather than a 500 that says the server is broken.
     *
     * <p>The constraint name identifies which rule fired, so it belongs in the log and not in the
     * response — it tells a caller what the tables are called and which column is unique.
     */
    @Test
    void refusedWrite_answers409AndNamesNoConstraintInTheResponse() throws UnsupportedEncodingException {
        MvcTestResult result = mockMvc.get().uri("/test-errors/duplicate").exchange();

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("DATA_CONFLICT");
        assertThat(result).bodyJson().extractingPath("$.messageCode").isEqualTo("MSG43");
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo(GlobalExceptionHandler.CONFLICT_DETAIL);
        assertThat(result).bodyJson().extractingPath("$.traceId").asString().isNotBlank();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("uq_user_account_email")
                .doesNotContain("user_account")
                .doesNotContain("duplicate key");
    }

    @Test
    void invalidRequestBody_answers400WithOneEntryPerOffendingField() {
        MvcTestResult result = mockMvc.post()
                .uri("/test-errors/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"  \", \"leverage\": 0}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.messageCode").isEqualTo("MSG01");
        assertThat(result).bodyJson().extractingPath("$.errors.name").isEqualTo("The name field is required.");
        assertThat(result).bodyJson().extractingPath("$.errors.leverage").isEqualTo("leverage must be at least 1.");
    }

    @Test
    void validRequestBody_passesThroughUntouched() {
        assertThat(mockMvc.post()
                        .uri("/test-errors/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"BTCUSDT\", \"leverage\": 10}"))
                .hasStatusOk();
    }

    @Test
    void validationFailure_reportsAWholeObjectErrorAndFallsBackWhenAConstraintHasNoMessage() throws Exception {
        MethodParameter parameter =
                new MethodParameter(ThrowingController.class.getDeclaredMethod("validate", PlanDraft.class), 0);
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "planDraft");
        binding.addError(new FieldError("planDraft", "entryPrice", null));
        binding.reject("plan.priceOrder", "Entry must differ from the stop loss.");

        ResponseEntity<Object> response = new GlobalExceptionHandler()
                .handleMethodArgumentNotValid(
                        new MethodArgumentNotValidException(parameter, binding),
                        new HttpHeaders(),
                        HttpStatus.BAD_REQUEST,
                        new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse()));

        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
        Map<?, ?> errors =
                (Map<?, ?>) ((ProblemDetail) response.getBody()).getProperties().get("errors");
        assertThat(errors.get("entryPrice")).isEqualTo(GlobalExceptionHandler.DEFAULT_FIELD_MESSAGE);
        assertThat(errors.get("planDraft")).isEqualTo("Entry must differ from the stop loss.");
    }

    @Test
    void unexpectedFailureOutsideARequest_stillCarriesAReferenceCode() {
        MDC.clear();

        ResponseEntity<ProblemDetail> response =
                new GlobalExceptionHandler().handleUnexpectedException(new IllegalStateException("boom"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties().get("traceId")).asString().isNotBlank();
    }

    @RestController
    @RequestMapping("/test-errors")
    static class ThrowingController {

        @GetMapping("/business")
        void business() {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Leverage 125 exceeds the maximum");
        }

        @GetMapping("/missing")
        void missing() {
            throw new ResourceNotFoundException("TradingPlan", 42);
        }

        @GetMapping("/broken")
        void broken() {
            throw new IllegalStateException("connection string postgres://user:secret@db/cryptopilot is invalid");
        }

        @GetMapping("/duplicate")
        void duplicate() {
            throw new DataIntegrityViolationException(
                    "could not execute statement",
                    new SQLException(
                            "ERROR: duplicate key value violates unique constraint \"uq_user_account_email\""));
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody PlanDraft draft) {
            // The body exists only to be validated; there is nothing to do with a valid one.
        }
    }

    record PlanDraft(
            @NotBlank(message = "The name field is required.")
            String name,

            @Min(value = 1, message = "leverage must be at least 1.")
            int leverage) {}
}
