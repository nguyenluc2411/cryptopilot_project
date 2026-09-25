package com.cryptopilot.common.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * The STOMP endpoint of TECHNICAL_DESIGN 9, under {@code cryptopilot.realtime}.
 *
 * <p>Rule: TECHNICAL_DESIGN 5.3 (origins restricted to the configured web origins) and 9.
 *
 * @param allowedOriginPatterns the browser origins that may open {@code /ws}
 * @param sendTimeLimit how long a send to one session may take before that session is closed
 * @param sendBufferSizeLimit how much may be buffered for one session before it is closed
 */
@Validated
@ConfigurationProperties("cryptopilot.realtime")
public record RealtimeProperties(
        @NotEmpty @DefaultValue("http://localhost:*") List<String> allowedOriginPatterns,
        @NotNull @DefaultValue("10s") Duration sendTimeLimit,
        @NotNull @DefaultValue("512KB") DataSize sendBufferSizeLimit) {}
