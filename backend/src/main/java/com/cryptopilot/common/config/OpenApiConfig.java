package com.cryptopilot.common.config;

import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ProblemDetails;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.math.BigDecimal;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document of the backend, served by springdoc at {@code /v3/api-docs} with Swagger UI at
 * {@code /swagger-ui.html}, and switched off in the {@code prod} profile (TECHNICAL_DESIGN 8).
 *
 * <p>Two things make the document describe what actually travels:
 *
 * <ul>
 *   <li>Every {@link BigDecimal} is a JSON <em>string</em> on the wire ({@link JacksonConfig}, ADR-008), so its schema is
 *       a string of format {@code decimal}, not a number a client generator would read into a double.
 *   <li>The bearer scheme is declared once, as {@value #BEARER}; the operations that need a session name it, and the
 *       public ones — sign-in and the market data — do not.
 * </ul>
 *
 * <p>Rule: TECHNICAL_DESIGN 1.3, 5.3, 5.4 and 8; SRS 3.1.3.
 * <p>Reference: OpenAPI Initiative. <i>OpenAPI Specification</i> 3.1 ("Security Scheme Object", HTTP bearer).
 */
@Configuration
public class OpenApiConfig {

    /** The name of the security scheme the protected operations refer to. */
    public static final String BEARER = "bearerAuth";

    /** The schema of the 429 body, in {@code components.schemas}. */
    static final String RATE_LIMITED_PROBLEM = "RateLimitedProblem";

    private static final String API = "/api/v1/";
    private static final String TOO_MANY_REQUESTS = "429";
    private static final String RETRY_AFTER = "Retry-After";

    static {
        SpringDocUtils.getConfig().replaceWithSchema(BigDecimal.class, new StringSchema().format("decimal"));
    }

    /** The document's header and its one security scheme. */
    @Bean
    OpenAPI cryptoPilotOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CryptoPilot API")
                        .version("v1")
                        .description("Decision support and paper trading. Times are ISO 8601 UTC; decimals are "
                                + "strings. Errors are RFC 9457 problem details carrying code, messageCode (SRS 5.3) "
                                + "and traceId."))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }

    /**
     * Adds the 429 of the request limits (ADR-014) to every operation under {@code /api/v1/}. The limiter is a servlet
     * filter, so no controller declares it and springdoc would not see it. An operation that already documents a 429
     * (the sign-in lockout, MSG09) keeps its text, with MSG50 added.
     *
     * <p>Rule: TECHNICAL_DESIGN 5.3 and 8; ADR-014.
     *
     * <p>Reference: Nottingham, M. &amp; Fielding, R. (2012). RFC 6585: Additional HTTP Status Codes, section 4. IETF;
     * Fielding, R., Nottingham, M. &amp; Reschke, J. (2022). RFC 9110: HTTP Semantics, section 10.2.3 (Retry-After).
     */
    @Bean
    OpenApiCustomizer rateLimitedResponses() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSchemas(RATE_LIMITED_PROBLEM, rateLimitedProblem());
            openApi.getPaths().forEach((path, item) -> {
                if (path.startsWith(API)) {
                    item.readOperations().forEach(OpenApiConfig::addRateLimited);
                }
            });
        };
    }

    private static void addRateLimited(Operation operation) {
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        String msg50 = "MSG50: too many requests in the current window; try again after Retry-After seconds";
        ApiResponse documented = operation.getResponses().get(TOO_MANY_REQUESTS);
        ApiResponse response = documented == null ? new ApiResponse().description(msg50) : documented;
        if (documented != null && !documented.getDescription().contains("MSG50")) {
            documented.description(documented.getDescription() + "; or " + msg50);
        }
        response.addHeaderObject(
                RETRY_AFTER,
                new Header().description("Seconds until the window ends").schema(new IntegerSchema()));
        Content content = response.getContent() == null ? new Content() : response.getContent();
        content.addMediaType(
                org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + RATE_LIMITED_PROBLEM)));
        response.setContent(content);
        operation.getResponses().addApiResponse(TOO_MANY_REQUESTS, response);
    }

    /** The problem body every API error carries (RFC 9457 plus code, messageCode and traceId), as a 429 fills it. */
    private static Schema<?> rateLimitedProblem() {
        return new ObjectSchema()
                .description("Problem details of a request refused by the request limits")
                .addProperty("type", new StringSchema().example("about:blank"))
                .addProperty("title", new StringSchema().example("Too Many Requests"))
                .addProperty("status", new IntegerSchema().example(429))
                .addProperty("detail", new StringSchema())
                .addProperty("instance", new StringSchema())
                .addProperty(ProblemDetails.CODE, new StringSchema().example(ErrorCode.RATE_LIMITED.code()))
                .addProperty(
                        ProblemDetails.MESSAGE_CODE, new StringSchema().example(ErrorCode.RATE_LIMITED.messageCode()))
                .addProperty(
                        "messageArgs",
                        new ArraySchema()
                                .items(new StringSchema())
                                .description("The seconds to wait, the same as Retry-After"))
                .addProperty(ProblemDetails.TRACE_ID, new StringSchema());
    }
}
