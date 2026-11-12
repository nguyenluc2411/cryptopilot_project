package com.cryptopilot.journal.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The review of a trade as the Trader saves it on SCR-21; it replaces the review stored before, so a field left out is
 * cleared. Nothing else of the trade can be sent: an execution fact, or any other field, is collected in
 * {@link #otherFields()} and refused by the service (BR-40).
 *
 * <p>Rule: BR-40; UC-23; SRS 3.6.1.
 *
 * @param strategyId the strategy the trade followed, or {@code null} for none
 * @param setupDescription the setup, up to 4,000 characters
 * @param lessonsLearned the lessons, up to 4,000 characters
 * @param mistakes the mistakes, up to 4,000 characters
 * @param chartImagePublicId the key of one chart image uploaded with the signed form, the key already stored to keep
 *     it, or {@code null} for none
 * @param otherFields every property of the body that is not one of the above
 */
public record JournalReviewRequest(
        UUID strategyId,
        @Size(max = 4000, message = "MSG15") String setupDescription,
        @Size(max = 4000, message = "MSG15") String lessonsLearned,
        @Size(max = 4000, message = "MSG15") String mistakes,
        String chartImagePublicId,
        @Schema(hidden = true) @JsonAnySetter Map<String, Object> otherFields) {

    public JournalReviewRequest {
        otherFields = otherFields == null ? Map.of() : new LinkedHashMap<>(otherFields);
    }
}
