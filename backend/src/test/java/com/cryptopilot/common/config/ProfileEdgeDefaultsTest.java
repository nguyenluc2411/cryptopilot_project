package com.cryptopilot.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * What each profile's files give the HTTP edge when the environment sets nothing: development may default to
 * localhost; production (staging runs the same profile, TD 12) has no permissive default and trusts nothing until the
 * environment says otherwise.
 *
 * <p>The real {@code application*.yml} files are read, with no system environment, so the test sees exactly the
 * fallbacks a deployment that forgot a variable would get.
 *
 * <p>Rule: TECHNICAL_DESIGN 5.3 and 12.
 */
class ProfileEdgeDefaultsTest {

    @Test
    void TD53_production_withNoEnvironment_allowsNoOriginAndTrustsNoProxy() throws IOException {
        Binder prod = binder("prod");

        assertThat(cors(prod).allowedOrigins()).isEmpty();
        assertThat(proxies(prod).trustedProxies()).isEmpty();
    }

    /** ADR-014: production always limits, at the TECHNICAL_DESIGN 5.3 values unless the environment says otherwise. */
    @Test
    void TD53_production_limitsRequestsAtTheDesignedRates() throws IOException {
        RateLimitProperties limits = rateLimits(binder("prod"));

        assertThat(limits.enabled()).isTrue();
        assertThat(limits.window()).hasMinutes(1);
        assertThat(limits.login()).isEqualTo(10);
        assertThat(limits.auth()).isEqualTo(20);
        assertThat(limits.api()).isEqualTo(120);
        assertThat(limits.paperOrders()).isEqualTo(50);
        assertThat(limits.paperOrdersWindow()).hasSeconds(10);
    }

    @Test
    void TD53_development_limitsTooButLoosely_andATestContextDoesNot() throws IOException {
        assertThat(rateLimits(binder("dev")).enabled()).isTrue();
        assertThat(rateLimits(binder("dev")).login()).isEqualTo(100);
        assertThat(rateLimits(binder("dev")).paperOrders()).isEqualTo(500);
        assertThat(rateLimits(binder()).enabled()).isFalse();
    }

    @Test
    void TD53_withoutAProfile_nothingIsAllowedOrTrustedEither() throws IOException {
        Binder base = binder();

        assertThat(cors(base).allowedOrigins()).isEmpty();
        assertThat(proxies(base).trustedProxies()).isEmpty();
    }

    @Test
    void TD53_development_defaultsToLocalhostOnly() throws IOException {
        Binder dev = binder("dev");

        assertThat(cors(dev).allowedOrigins()).containsExactly("http://localhost:*", "http://127.0.0.1:*");
        assertThat(proxies(dev).trustedProxies()).containsExactly("127.0.0.1", "::1");
    }

    static CorsProperties cors(Binder binder) {
        return binder.bindOrCreate("cryptopilot.web.cors", Bindable.of(CorsProperties.class));
    }

    static RateLimitProperties rateLimits(Binder binder) {
        return binder.bindOrCreate("cryptopilot.web.rate-limit", Bindable.of(RateLimitProperties.class));
    }

    static TrustedProxyProperties proxies(Binder binder) {
        return binder.bindOrCreate("cryptopilot.web", Bindable.of(TrustedProxyProperties.class));
    }

    /** The base file plus the profiles' files, profiles first (they win), and no system environment. */
    static Binder binder(String... profiles) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String profile : profiles) {
            load(loader, "application-" + profile + ".yml").forEach(sources::addFirst);
        }
        load(loader, "application.yml").forEach(sources::addLast);
        return new Binder(
                ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(environment));
    }

    private static List<PropertySource<?>> load(YamlPropertySourceLoader loader, String file) throws IOException {
        return loader.load(file, new ClassPathResource(file));
    }
}
