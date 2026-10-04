package com.cryptopilot.common.config;

import com.cryptopilot.common.web.ClientAddressFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The filters of the HTTP edge that every module shares, registered here with an explicit order rather than as
 * components, so an MVC test slice does not pick them up without the settings they need.
 *
 * <p>Rule: NSF-18; TECHNICAL_DESIGN 5.3.
 */
@Configuration
@EnableConfigurationProperties(TrustedProxyProperties.class)
public class WebEdgeConfig {

    @Bean
    public FilterRegistrationBean<ClientAddressFilter> clientAddressFilter(TrustedProxyProperties properties) {
        FilterRegistrationBean<ClientAddressFilter> registration =
                new FilterRegistrationBean<>(new ClientAddressFilter(properties.trustedProxies()));
        registration.setOrder(ClientAddressFilter.ORDER);
        return registration;
    }
}
