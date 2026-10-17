package com.cryptopilot.paper.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The commission of paper orders, under {@code cryptopilot.paper.order}: a share of what a fill trades, taken in the
 * coin the fill brings in, as Binance charges an account that pays no fee in BNB. The defaults are Binance's standard
 * Spot rates, 0.1% maker and taker. An invalid value stops the application at start-up.
 *
 * <p>Rule: TR-02.
 *
 * @param spotMakerFeeRate the rate of a Spot fill that waited in the book, e.g. {@code 0.001} for 0.1%
 * @param spotTakerFeeRate the rate of a Spot fill that executed on arrival
 */
@Validated
@ConfigurationProperties("cryptopilot.paper.order")
public record PaperOrderProperties(
        @NotNull @DecimalMin("0") @DecimalMax("0.1") @DefaultValue("0.001")
        BigDecimal spotMakerFeeRate,

        @NotNull @DecimalMin("0") @DecimalMax("0.1") @DefaultValue("0.001")
        BigDecimal spotTakerFeeRate) {}
