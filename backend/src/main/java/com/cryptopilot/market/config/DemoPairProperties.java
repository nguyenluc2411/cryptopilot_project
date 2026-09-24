package com.cryptopilot.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Whether the demo pairs are activated automatically, under {@code cryptopilot.market.demo}.
 *
 * <p>{@code true} in the {@code dev} profile only (Q-16): until the administrator screen of UC-45 exists nothing
 * else can activate a pair, and without an active pair NSF-03 opens no stream. Off by default and never set in
 * {@code prod}, where activation stays an administrator's decision (BR-07).
 *
 * <p>Rule: BR-07; Q-16.
 *
 * @param activateSeedSymbols whether the seed symbols of {@code cryptopilot.market.sync} are activated on every
 *     market where the exchange lists them as TRADING, once per market after the first symbol synchronisation
 */
@ConfigurationProperties("cryptopilot.market.demo")
public record DemoPairProperties(@DefaultValue("false") boolean activateSeedSymbols) {}
