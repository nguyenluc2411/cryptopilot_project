package com.cryptopilot.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.math.BigDecimal;
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
}
