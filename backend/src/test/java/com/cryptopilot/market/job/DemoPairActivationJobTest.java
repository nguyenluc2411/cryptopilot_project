package com.cryptopilot.market.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.config.DemoPairProperties;
import com.cryptopilot.market.config.SymbolSyncProperties;
import com.cryptopilot.market.event.SymbolsSynchronised;
import com.cryptopilot.market.service.DemoPairActivation;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * When the demo pairs are activated: in {@code dev} only, once per market after its first synchronisation; never
 * by default or in {@code prod}, where BR-07 keeps activation an administrator's decision.
 *
 * <p>Rule: BR-07; Q-16.
 */
class DemoPairActivationJobTest {

    private static final List<String> SEEDS = List.of("BTCUSDT", "ETHUSDT");

    private final List<String> calls = new ArrayList<>();
    private boolean failing;

    /** Q-16: the dev profile turns it on; the default and prod configurations do not. */
    @Test
    void Q16_onlyTheDevProfile_activatesTheDemoPairs() throws Exception {
        assertThat(bind()).isEqualTo(new DemoPairProperties(false));
        assertThat(bind("application-prod.yml")).isEqualTo(new DemoPairProperties(false));
        assertThat(bind("application-dev.yml")).isEqualTo(new DemoPairProperties(true));
    }

    /** Q-16: enabled, each market's first synchronisation activates its seeds, and later ones do not. */
    @Test
    void Q16_enabled_activatesOncePerMarket() {
        DemoPairActivationJob job = job(true);

        job.onSymbolsSynchronised(new SymbolsSynchronised(MarketType.SPOT));
        job.onSymbolsSynchronised(new SymbolsSynchronised(MarketType.SPOT));
        job.onSymbolsSynchronised(new SymbolsSynchronised(MarketType.FUTURES));

        assertThat(calls).containsExactly("SPOT [BTCUSDT, ETHUSDT]", "FUTURES [BTCUSDT, ETHUSDT]");
    }

    /** BR-07: disabled — the default and prod — nothing is ever activated. */
    @Test
    void BR07_disabled_activatesNothing() {
        job(false).onSymbolsSynchronised(new SymbolsSynchronised(MarketType.SPOT));

        assertThat(calls).isEmpty();
    }

    /** A failed activation is tried again after the next synchronisation. */
    @Test
    void Q16_aFailedActivation_isTriedAgain() {
        DemoPairActivationJob job = job(true);
        failing = true;
        job.onSymbolsSynchronised(new SymbolsSynchronised(MarketType.SPOT));
        failing = false;

        job.onSymbolsSynchronised(new SymbolsSynchronised(MarketType.SPOT));

        assertThat(calls).containsExactly("SPOT [BTCUSDT, ETHUSDT]", "SPOT [BTCUSDT, ETHUSDT]");
    }

    private DemoPairActivationJob job(boolean enabled) {
        DemoPairActivation activation = new DemoPairActivation(null) {
            @Override
            public List<String> activate(MarketType market, List<String> symbols) {
                calls.add(market + " " + symbols);
                if (failing) {
                    throw new IllegalStateException("the database is down");
                }
                return symbols;
            }
        };
        return new DemoPairActivationJob(
                activation,
                new DemoPairProperties(enabled),
                new SymbolSyncProperties(true, "0 5 0 * * *", ZoneOffset.UTC, SEEDS));
    }

    /** Binds the properties from application.yml overlaid with a profile file, as Spring Boot would. */
    private static DemoPairProperties bind(String... profileFiles) throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = new ArrayList<>();
        for (String file : profileFiles) {
            sources.addAll(loader.load(file, new ClassPathResource(file)));
        }
        sources.addAll(loader.load("application.yml", new ClassPathResource("application.yml")));
        return new Binder(org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources))
                .bindOrCreate("cryptopilot.market.demo", DemoPairProperties.class);
    }
}
