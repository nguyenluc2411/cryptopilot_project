package com.cryptopilot.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.CryptoPilotApplication;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * The demo profile refuses to start on production and starts on development and staging (D-120).
 *
 * <p>Three levels: the guard on a hand-made environment; the guard on the real configuration files, so the defaults
 * each profile gives {@code cryptopilot.environment} are the ones checked; and a real start of the application on
 * {@code prod,demo}, which proves the guard is registered and stops the start before any bean, Flyway included.
 *
 * <p>Rule: DEMO-01; D-62, D-120.
 */
class DemoEnvironmentGuardTest {

    private final DemoEnvironmentGuard guard = new DemoEnvironmentGuard();

    @Test
    void D120_theDemoProfile_onProduction_isRefusedWithAMessageNamingTheFix() {
        assertThatThrownBy(() -> guard.postProcessEnvironment(environment("production", "prod", "demo"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'production'")
                .hasMessageContaining("CRYPTOPILOT_ENVIRONMENT")
                .hasMessageContaining("SPRING_PROFILES_ACTIVE");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"development", "staging", "Staging", " staging "})
    void D120_theDemoProfile_onDevelopmentOrStaging_starts(String name) {
        assertThatCode(() -> guard.postProcessEnvironment(environment(name, "prod", "demo"), null))
                .doesNotThrowAnyException();
    }

    /** Fail closed: a typo or a missing name must not pass for an allowed environment. */
    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"prod", "Production", "stage", ""})
    void D120_theDemoProfile_onAnyOtherName_isRefused(String name) {
        assertThatThrownBy(() -> guard.postProcessEnvironment(environment(name, "demo"), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void D120_withoutAnEnvironmentName_theDemoProfileIsRefused() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("demo");

        assertThatThrownBy(() -> guard.postProcessEnvironment(environment, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void D120_production_withoutTheDemoProfile_starts() {
        assertThatCode(() -> guard.postProcessEnvironment(environment("production", "prod"), null))
                .doesNotThrowAnyException();
    }

    /** Staging that forgot CRYPTOPILOT_ENVIRONMENT reads the prod profile's default, production, and stops. */
    @Test
    void D120_theConfigurationFiles_onProdAndDemo_withNoEnvironment_areRefused() throws IOException {
        assertThatThrownBy(() -> guard.postProcessEnvironment(fromFiles("prod", "demo"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'production'");
    }

    @Test
    void D120_theConfigurationFiles_onDemoAlone_defaultToDevelopment_andStart() throws IOException {
        assertThatCode(() -> guard.postProcessEnvironment(fromFiles("demo"), null))
                .doesNotThrowAnyException();
    }

    @Test
    void D120_theConfigurationFiles_onProdAndDemo_withStagingSet_start() throws IOException {
        StandardEnvironment environment = fromFiles("prod", "demo");
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("deployment", Map.of("CRYPTOPILOT_ENVIRONMENT", "staging")));

        assertThatCode(() -> guard.postProcessEnvironment(environment, null)).doesNotThrowAnyException();
    }

    /**
     * The application itself, started as a production deployment that also lists demo: it stops while the environment
     * is prepared. No database is configured, so getting as far as Flyway would fail with a different error.
     */
    @Test
    void D120_theApplication_startedOnProductionWithTheDemoProfile_refusesToStart() {
        SpringApplication application = new SpringApplication(CryptoPilotApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("prod", "demo");

        assertThatThrownBy(() -> application.run("--cryptopilot.environment=production"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("The demo profile loads the demo accounts and is refused on 'production'");
    }

    private static MockEnvironment environment(String name, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        environment.setProperty(DemoEnvironmentGuard.ENVIRONMENT_PROPERTY, name);
        return environment;
    }

    /** The base file plus the profiles' files, later profiles winning, and no system environment. */
    private static StandardEnvironment fromFiles(String... profiles) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(profiles);
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String profile : profiles) {
            String file = "application-" + profile + ".yml";
            loader.load(file, new ClassPathResource(file)).forEach(sources::addFirst);
        }
        String base = "application.yml";
        loader.load(base, new ClassPathResource(base)).forEach(sources::addLast);
        return environment;
    }
}
