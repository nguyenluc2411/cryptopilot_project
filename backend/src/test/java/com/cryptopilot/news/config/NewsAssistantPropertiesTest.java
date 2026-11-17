package com.cryptopilot.news.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The news budget of one AI Assistant question: the defaults bind, and a configuration that would carry more
 * articles than the context limit allows refuses to start.
 *
 * <p>Rule: NSF-16; T-074.
 */
class NewsAssistantPropertiesTest {

    private final ApplicationContextRunner contexts =
            new ApplicationContextRunner().withUserConfiguration(BindNewsAssistantProperties.class);

    @Test
    void NSF16_theDefaults_bindWithinTheBudget() {
        contexts.run(context -> {
            NewsAssistantProperties properties = context.getBean(NewsAssistantProperties.class);
            assertThat(properties.coinArticles()).isEqualTo(5);
            assertThat(properties.marketArticles()).isEqualTo(3);
            assertThat(properties.summaryChars()).isEqualTo(400);
        });
    }

    @Test
    void NSF16_moreCoinAndMarketArticlesThanTheBudget_failsToBind() {
        contexts.withPropertyValues(
                        "cryptopilot.news.assistant.coin-articles=6", "cryptopilot.news.assistant.market-articles=3")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("coin-articles + market-articles must be at most 8"));
    }

    @Test
    void NSF16_aSummaryLongerThanTheLimit_failsToBind() {
        contexts.withPropertyValues("cryptopilot.news.assistant.summary-chars=601")
                .run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties(NewsAssistantProperties.class)
    static class BindNewsAssistantProperties {}
}
