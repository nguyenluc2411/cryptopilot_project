package com.cryptopilot.notification.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The settings of the in-app notifications: their retention sweep (NSF-17). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationSweepProperties.class)
public class NotificationConfig {}
