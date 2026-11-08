package com.cryptopilot.billing.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The reconciliation of unconfirmed orders (NSF-13): a PENDING order with no IPN after {@code after} is asked about at
 * its gateway, every {@code interval}, at most {@code batchSize} orders a run.
 *
 * @param enabled whether the run is scheduled; off without a profile so a test context schedules nothing
 * @param interval the time between the end of one run and the start of the next
 * @param after how old a PENDING order is before its gateway is asked about it
 * @param batchSize the most orders one run asks about, oldest first; the rest wait for the next run
 */
@Validated
@ConfigurationProperties("cryptopilot.billing.reconcile")
public record ReconcileProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("PT5M") Duration interval,
        @NotNull @DefaultValue("PT5M") Duration after,
        @Min(1) @Max(500) @DefaultValue("50") int batchSize) {}
