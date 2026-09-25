package com.cryptopilot.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.support.TestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The OpenAPI document of TECHNICAL_DESIGN 8: served at {@code /v3/api-docs} to anyone where it is enabled, listing
 * every HTTP operation of auth, user and market, with decimals described as strings and the bearer scheme on the
 * operations that need a session — and switched off by the {@code prod} profile.
 *
 * <p>Rule: TECHNICAL_DESIGN 1.3, 5.4 and 8; SRS 3.1.3.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class OpenApiDocsTest {

    /** Every path the controllers map, and the number of operations under them. */
    private static final Set<String> PATHS = Set.of(
            "/api/v1/auth/register",
            "/api/v1/auth/verify-email",
            "/api/v1/auth/resend-verification",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/me/password",
            "/api/v1/me/profile",
            "/api/v1/me/notification-preferences",
            "/api/v1/me/devices",
            "/api/v1/me/devices/{deviceId}",
            "/api/v1/market/pairs",
            "/api/v1/market/{market}/{symbol}/candles",
            "/api/v1/market/spot/{symbol}/stats",
            "/api/v1/market/futures/{symbol}/stats",
            "/api/v1/market/futures/{symbol}/metrics");

    private static final int OPERATIONS = 19;

    @Autowired
    private MockMvc mvc;

    @Test
    void TD8_theDocument_isServedToAGuest_andListsEveryOperation() throws Exception {
        String body = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Map<String, Map<String, Object>> paths = JsonPath.read(body, "$.paths");
        assertThat(paths.keySet()).containsExactlyInAnyOrderElementsOf(PATHS);
        int operations = 0;
        for (Map<String, Object> path : paths.values()) {
            operations += (int) path.keySet().stream()
                    .filter(key ->
                            Set.of("get", "put", "post", "delete", "patch").contains(key))
                    .count();
        }
        assertThat(operations).isEqualTo(OPERATIONS);
        assertThat((String) JsonPath.read(body, "$.info.title")).isEqualTo("CryptoPilot API");
    }

    /** TECHNICAL_DESIGN 5.4: a decimal travels as a string, and the document says so. */
    @Test
    void TD54_decimals_areDescribedAsStrings() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(body, "$.components.schemas.PairResponse.properties.tickSize.type"))
                .isEqualTo("string");
        assertThat((String) JsonPath.read(body, "$.components.schemas.PairResponse.properties.tickSize.format"))
                .isEqualTo("decimal");
    }

    /** The session-only operations name the bearer scheme; sign-in and the market data do not. */
    @Test
    void SRS313_onlyTheOperationsThatNeedASession_nameTheBearerScheme() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();

        assertThat(securityOf(body, "/api/v1/me/profile", "get")).containsExactly(OpenApiConfig.BEARER);
        assertThat(securityOf(body, "/api/v1/me/password", "put")).containsExactly(OpenApiConfig.BEARER);
        assertThat(securityOf(body, "/api/v1/me/devices", "post")).containsExactly(OpenApiConfig.BEARER);
        assertThat(securityOf(body, "/api/v1/auth/login", "post")).isEmpty();
        assertThat(securityOf(body, "/api/v1/market/pairs", "get")).isEmpty();
        assertThat((String) JsonPath.read(body, "$.components.securitySchemes.bearerAuth.scheme"))
                .isEqualTo("bearer");
    }

    /** Swagger UI is reachable for reading where the document is. */
    @Test
    void TD8_swaggerUi_isReachable() throws Exception {
        int status =
                mvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();

        assertThat(status).isIn(200, 302);
    }

    /** TECHNICAL_DESIGN 8: the prod profile turns off both the document and Swagger UI; the default keeps them. */
    @Test
    void TD8_theProdProfile_switchesTheDocumentOff() throws Exception {
        assertThat(flag("springdoc.api-docs.enabled", "application-prod.yml")).isFalse();
        assertThat(flag("springdoc.swagger-ui.enabled", "application-prod.yml")).isFalse();
        assertThat(flag("springdoc.api-docs.enabled")).isTrue();
        assertThat(flag("springdoc.api-docs.enabled", "application-dev.yml")).isTrue();
    }

    private static List<String> securityOf(String body, String path, String method) {
        List<String> names = new ArrayList<>();
        try {
            List<Map<String, Object>> requirements =
                    JsonPath.read(body, "$.paths['" + path + "']." + method + ".security");
            requirements.forEach(requirement -> names.addAll(requirement.keySet()));
        } catch (PathNotFoundException publicOperation) {
            // No security key: the operation is public.
        }
        return names;
    }

    /** A boolean as the given profile files over application.yml resolve it; springdoc's own default is true. */
    private static boolean flag(String name, String... profileFiles) throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = new ArrayList<>();
        for (String file : profileFiles) {
            sources.addAll(loader.load(file, new ClassPathResource(file)));
        }
        sources.addAll(loader.load("application.yml", new ClassPathResource("application.yml")));
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind(name, Boolean.class)
                .orElse(true);
    }
}
