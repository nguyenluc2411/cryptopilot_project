package com.cryptopilot.news.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/**
 * How NSF-11 summarizes articles, under {@code cryptopilot.news.summary}. The model, the instructions and the limits
 * are configuration, not code: the model is chosen per environment ({@code NEWS_SUMMARY_MODEL}), the instructions are a
 * text file with {@code {language}}, {@code {maxWords}} and {@code {maxTitleWords}} placeholders, and the AI Service receives them with every
 * request.
 *
 * <p>Rule: NSF-11 (articles without a summary, a summary of at most 60 words and a sentiment label, failures retried at
 * the next run); BR-48 (the article text read for the summary is not stored).
 *
 * @param enabled whether the summary job is scheduled; off without a profile
 * @param period the pause between the end of one run and the start of the next (NSF-11: 5 minutes)
 * @param initialDelay how long after start-up the first run waits
 * @param maxArticlesPerRun the most articles one run sends, which bounds the cost of a run
 * @param maxAge how old an article may be and still be summarized; older ones keep their excerpt
 * @param maxAttempts how many times the job asks the model for one article before it waits for an administrator
 *     (D-128)
 * @param model the provider's model identifier
 * @param temperature the sampling temperature; low, because a summary reports and does not invent
 * @param maxOutputTokens the longest answer the model may write, reasoning included
 * @param language the language the summary is written in, as the prompt names it
 * @param maxWords the longest summary stored (NSF-11: 60 words)
 * @param maxTitleWords the longest translated headline stored (D-127)
 * @param maxInputWords the most words of an article sent to the model
 * @param minBodyWords below this many words a page's text is not the article (a paywall, a cookie wall) and the
 *     feed's excerpt is used instead
 * @param prompt the instructions, a text resource
 */
@Validated
@ConfigurationProperties("cryptopilot.news.summary")
public record NewsSummaryProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("5m") Duration period,
        @NotNull @DefaultValue("2m") Duration initialDelay,
        @Min(1) @Max(200) @DefaultValue("10") int maxArticlesPerRun,
        @NotNull @DefaultValue("7d") Duration maxAge,
        @Min(1) @Max(20) @DefaultValue("3") int maxAttempts,
        @NotBlank String model,

        @DecimalMin("0.0") @DecimalMax("2.0") @DefaultValue("0.2")
        double temperature,

        @Min(16) @Max(8192) @DefaultValue("512") int maxOutputTokens,
        @NotBlank @DefaultValue("Vietnamese") String language,
        @Min(10) @Max(200) @DefaultValue("60") int maxWords,
        @Min(5) @Max(100) @DefaultValue("30") int maxTitleWords,
        @Min(50) @Max(5000) @DefaultValue("1200") int maxInputWords,
        @Min(0) @DefaultValue("80") int minBodyWords,

        @NotNull @DefaultValue("classpath:prompts/news-summary.txt")
        Resource prompt) {}
