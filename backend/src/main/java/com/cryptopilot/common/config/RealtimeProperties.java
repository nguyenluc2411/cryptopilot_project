package com.cryptopilot.common.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * The STOMP endpoint of TECHNICAL_DESIGN 9, under {@code cryptopilot.realtime}.
 *
 * <p>Rule: TECHNICAL_DESIGN 9. The origins that may open {@code /ws} are the CORS origins, {@link CorsProperties}.
 *
 * @param sendTimeLimit how long a send to one session may take before that session is closed
 * @param sendBufferSizeLimit how much may be buffered for one session before it is closed
 */
@Validated
@ConfigurationProperties("cryptopilot.realtime")
public record RealtimeProperties(
        @NotNull @DefaultValue("10s") Duration sendTimeLimit,
        @NotNull @DefaultValue("512KB") DataSize sendBufferSizeLimit) {}
