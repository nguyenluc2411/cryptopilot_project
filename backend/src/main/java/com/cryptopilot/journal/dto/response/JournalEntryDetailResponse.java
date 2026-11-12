package com.cryptopilot.journal.dto.response;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * One trade as SCR-21 shows it: the fields of its row of SCR-20, at the top level of the JSON, and the Trader's review.
 *
 * <p>Rule: SRS 3.6.1; UC-22, UC-23; BR-40, BR-70.
 *
 * @param entry the facts, as {@link JournalEntryResponse} holds them
 * @param setupDescription the setup, or {@code null} until reviewed
 * @param lessonsLearned the lessons, or {@code null} until reviewed
 * @param mistakes the mistakes, or {@code null} until reviewed
 * @param chartImageUrl the chart image, or {@code null} without one
 * @param learning the learning data of a SIMULATED trade (BR-70); {@code null} for a MANUAL one
 */
public record JournalEntryDetailResponse(
        @JsonUnwrapped JournalEntryResponse entry,
        String setupDescription,
        String lessonsLearned,
        String mistakes,
        String chartImageUrl,
        FillLearningResponse learning) {}
