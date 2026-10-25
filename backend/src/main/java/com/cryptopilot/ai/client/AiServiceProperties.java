package com.cryptopilot.ai.client;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Where the AI Service is and how the backend proves itself to it, under {@code cryptopilot.ai.service}, from the
 * environment ({@code AI_SERVICE_URL}, {@code AI_SERVICE_SHARED_SECRET}). The LLM key is not here: only the AI
 * Service holds it (ADR-004).
 *
 * <p>An empty secret is allowed at start-up, so a context that never calls the service (a test, a profile without
 * the AI Service) still starts; a call without one fails as {@code NOT_CONFIGURED}.
 *
 * @param url the AI Service, e.g. {@code http://localhost:7070}
 * @param sharedSecret the secret the AI Service expects in {@code x-ai-service-secret}
 * @param connectTimeout how long to wait for a connection
 * @param requestTimeout how long one call may take: above the AI Service's own 60-second limit and its one retry
 */
@Validated
@ConfigurationProperties("cryptopilot.ai.service")
public record AiServiceProperties(
        @NotNull @DefaultValue("http://localhost:7070") URI url,
        @DefaultValue("") String sharedSecret,
        @NotNull @DefaultValue("5s") Duration connectTimeout,
        @NotNull @DefaultValue("130s") Duration requestTimeout) {

    /** Whether a call can be made at all. */
    public boolean configured() {
        return sharedSecret != null && !sharedSecret.isBlank();
    }
}
