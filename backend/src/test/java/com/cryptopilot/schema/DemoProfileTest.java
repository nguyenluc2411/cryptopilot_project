package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Which configuration files put the demo set on the Flyway path (DEMO-01, D-62). The base configuration and the
 * {@code prod} profile must not: a production deployment runs {@code prod} alone. The {@code dev} profile and the
 * {@code demo} profile that staging adds ({@code prod,demo}) must, with the production path first.
 *
 * <p>{@code SeedDataTest} asserts the same of the running test context; this reads each file on its own, so a profile
 * the suite never starts is checked too.
 *
 * <p>Rule: D-19, D-62; DEMO-01.
 */
class DemoProfileTest {

    @ParameterizedTest(name = "{0}: demo set {1}")
    @CsvSource({
        "application.yml,      false",
        "application-prod.yml, false",
        "application-dev.yml,  true",
        "application-demo.yml, true"
    })
    void D62_onlyTheDevAndDemoProfiles_addTheDemoLocation(String file, boolean addsDemo) {
        String locations = flywayLocations(file);

        if (addsDemo) {
            assertThat(locations).isEqualTo("classpath:db/migration,classpath:db/demo");
        } else {
            assertThat(locations)
                    .as("%s may leave the locations to the base configuration, but never name the demo set", file)
                    .satisfiesAnyOf(value -> assertThat(value).isNull(), value -> assertThat(value)
                            .isEqualTo("classpath:db/migration"));
        }
    }

    private static String flywayLocations(String file) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(file));
        Properties properties = yaml.getObject();
        assertThat(properties).as(file).isNotNull();
        return properties.getProperty("spring.flyway.locations");
    }
}
