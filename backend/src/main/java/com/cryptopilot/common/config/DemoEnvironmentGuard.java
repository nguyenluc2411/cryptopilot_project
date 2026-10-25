package com.cryptopilot.common.config;

import java.util.Locale;
import java.util.Set;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses to start the application when the {@code demo} profile is active anywhere but development or staging. The
 * demo profile puts the demo accounts on the Flyway path, so on production it would write sign-in-able accounts into
 * the real database.
 *
 * <p>It runs while the environment is prepared, after the configuration files are read and before any bean exists, so
 * Flyway never gets the chance to apply the demo set. {@code cryptopilot.environment} names the deployment: the base
 * configuration defaults it to {@code development}, the {@code prod} profile to {@code production}, and staging sets
 * {@code CRYPTOPILOT_ENVIRONMENT=staging}. A value outside the two allowed ones, a typo included, is refused as well, so
 * a staging deployment that forgot the variable stops rather than passing for production or the other way round.
 *
 * <p>Registered in {@code META-INF/spring.factories}, not as a bean: a bean would be created after Flyway has run.
 *
 * <p>Rule: DEMO-01; D-62, D-120.
 *
 * <p>Reference: Humble, J., &amp; Farley, D. (2010). Continuous Delivery, ch. 12. Addison-Wesley (sample data is
 * applied per environment and never reaches production).
 */
public class DemoEnvironmentGuard implements EnvironmentPostProcessor, Ordered {

    static final String DEMO_PROFILE = "demo";
    static final String ENVIRONMENT_PROPERTY = "cryptopilot.environment";
    static final Set<String> DEMO_ENVIRONMENTS = Set.of("development", "staging");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.matchesProfiles(DEMO_PROFILE)) {
            return;
        }
        String name = environment.getProperty(ENVIRONMENT_PROPERTY, "");
        if (!DEMO_ENVIRONMENTS.contains(name.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("The demo profile loads the demo accounts and is refused on '" + name
                    + "': it runs only where " + ENVIRONMENT_PROPERTY + " (CRYPTOPILOT_ENVIRONMENT) is development or"
                    + " staging. On production remove 'demo' from SPRING_PROFILES_ACTIVE; on staging set"
                    + " CRYPTOPILOT_ENVIRONMENT=staging.");
        }
    }

    /** After the configuration files are read, so their profiles and the environment's name are both known. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
