package com.cryptopilot.paper.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The rules of paper Futures that depend on how current the mark price is (plan 8.2.1), under
 * {@code cryptopilot.paper.futures}. The exchange streams the mark every second, so one older than {@code markMaxAge}
 * means a lost stream: its pair is {@code MARK_STALE}, where only closing and cancelling go on.
 *
 * <p>Rule: TR-20, TR-22a; D-127.
 *
 * @param markMaxAge the oldest mark price a decision that opens risk is taken on
 * @param staleMarkHaircut the share of a stale pair's notional taken off the cross wallet's free cash, on top of any
 *     loss at its last mark, when an isolated position is funded meanwhile (8.2.1 rule 4)
 * @param triggerProtect how far apart, as a share of the mark, the mark and the last price may be when a conditional
 *     order with {@code priceProtect} triggers; further apart it waits for the next price (the exchange's
 *     {@code triggerProtect} of the pair, which the symbol synchronisation does not read yet)
 * @param maxCallbackRate the largest callback of a trailing stop, in percent (plan 5.5: 20 in the exchange's current
 *     algo documentation; a setting in case it changes)
 */
@Validated
@ConfigurationProperties(prefix = "cryptopilot.paper.futures")
public record PaperFuturesProperties(
        @NotNull @DefaultValue("5s") Duration markMaxAge,

        @NotNull @DecimalMin("0") @DecimalMax("1") @DefaultValue("0.01")
        BigDecimal staleMarkHaircut,

        @NotNull @DecimalMin("0") @DecimalMax("1") @DefaultValue("0.05")
        BigDecimal triggerProtect,

        @NotNull @DecimalMin("0.1") @DefaultValue("20") BigDecimal maxCallbackRate) {}
