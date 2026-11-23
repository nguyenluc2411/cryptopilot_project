package com.cryptopilot.common.config;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Replaces Spring Modulith's completion advisor by {@link RegisteredListenersOnlyAdvisor} (TR-32): the after-commit
 * listeners that the event publication registry does not record stop paying two extra transactions and a second
 * connection on every delivery. The replacement keeps the name and the role of the bean it replaces.
 *
 * <p>Rule: ADR-010; TR-32.
 */
@Configuration(proxyBeanMethods = false)
public class EventListenerAdviceConfig {

    /** The name Modulith's {@code EventPublicationConfiguration} registers its advisor under. */
    static final String ADVISOR = "completionRegisteringAdvisor";

    @Bean
    static BeanDefinitionRegistryPostProcessor registeredListenersOnly() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                if (!registry.containsBeanDefinition(ADVISOR)) {
                    return;
                }
                BeanDefinition original = registry.getBeanDefinition(ADVISOR);
                RootBeanDefinition narrowed = new RootBeanDefinition(RegisteredListenersOnlyAdvisor.class);
                narrowed.setRole(original.getRole());
                narrowed.setAutowireMode(RootBeanDefinition.AUTOWIRE_CONSTRUCTOR);
                registry.removeBeanDefinition(ADVISOR);
                registry.registerBeanDefinition(ADVISOR, narrowed);
            }
        };
    }
}
