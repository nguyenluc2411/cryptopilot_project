package com.cryptopilot.common.config;

import com.cryptopilot.common.web.ClientAddressFilter;
import com.cryptopilot.common.web.FixedWindowRateLimiter;
import com.cryptopilot.common.web.RateLimitFilter;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The filters of the HTTP edge that every module shares, registered here with an explicit order rather than as
 * components, so an MVC test slice does not pick them up without the settings they need.
 *
 * <p>Rule: NSF-18; TECHNICAL_DESIGN 5.3; ADR-014.
 */
@Configuration
@EnableConfigurationProperties({TrustedProxyProperties.class, RateLimitProperties.class})
public class WebEdgeConfig {

    @Bean
    public FilterRegistrationBean<ClientAddressFilter> clientAddressFilter(TrustedProxyProperties properties) {
        FilterRegistrationBean<ClientAddressFilter> registration =
                new FilterRegistrationBean<>(new ClientAddressFilter(properties.trustedProxies()));
        registration.setOrder(ClientAddressFilter.ORDER);
        return registration;
    }

    /** The limits of ADR-014; registered but disabled when {@code cryptopilot.web.rate-limit.enabled} is false. */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            RateLimitProperties properties, StringRedisTemplate redis, Clock clock, ObjectMapper json) {
        RateLimitFilter filter = new RateLimitFilter(
                new RateLimitFilter.Limits(
                        properties.enabled(), properties.login(), properties.auth(), properties.api()),
                new FixedWindowRateLimiter(redis, clock, properties.window()),
                json);
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(RateLimitFilter.ORDER);
        registration.setEnabled(properties.enabled());
        return registration;
    }
}
