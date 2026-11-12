package com.cryptopilot.news.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How much news one AI Assistant question carries, under {@code cryptopilot.news.assistant}. Small on purpose: every
 * article goes to the model with the question, so these limits bound the context and the tokens it costs.
 *
 * <p>Rule: BR-51, NSF-16 (the context stays within what the AI Service accepts); T-074.
 *
 * @param coinArticles the most articles about the coin asked about
 * @param coinWindow how far back an article about the coin may have been published
 * @param marketArticles the most articles that name no coin of the system (the market in general)
 * @param marketWindow how far back a market article may have been published
 * @param summaryChars the longest summary sent for one article; longer ones are cut at a word
 */
@Validated
@ConfigurationProperties("cryptopilot.news.assistant")
public record NewsAssistantProperties(
        @Min(0) @Max(10) @DefaultValue("5") int coinArticles,
        @NotNull @DefaultValue("72h") Duration coinWindow,
        @Min(0) @Max(10) @DefaultValue("3") int marketArticles,
        @NotNull @DefaultValue("24h") Duration marketWindow,
        @Min(80) @Max(2000) @DefaultValue("400") int summaryChars) {}
