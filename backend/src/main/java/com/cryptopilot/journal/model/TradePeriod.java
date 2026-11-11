package com.cryptopilot.journal.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A resolved period of the journal and of the statistics: UTC+7 days {@code [from, to)} and the instants they start at.
 *
 * <p>Rule: SRS 3.6.1, 3.6.3; UC-22, UC-25; CR-08; D-138, D-139.
 *
 * @param from the first day counted, or {@code null} when the period has no start
 * @param to the first day no longer counted
 * @param start the first instant counted, or {@code null} when the period has no start
 * @param end the first instant no longer counted
 */
public record TradePeriod(LocalDate from, LocalDate to, Instant start, Instant end) {}
