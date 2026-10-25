package com.cryptopilot.journal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The settings of the journal: the correction of incomplete funding (D-97 note of 2026-10-24). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FundingCorrectionProperties.class)
public class JournalConfig {}
