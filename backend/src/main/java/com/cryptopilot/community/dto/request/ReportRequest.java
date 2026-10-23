package com.cryptopilot.community.dto.request;

import com.cryptopilot.community.model.enums.ReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The report form of SCR-27 (UC-33). Leading and trailing white space of the description is removed, and a blank one
 * counts as none.
 *
 * <p>Rule: UC-33; SRS 3.7.4.
 *
 * @param reason SPAM, SCAM, ABUSE, MISLEADING or OTHER
 * @param description at most 500 characters; required for OTHER
 */
public record ReportRequest(
        @NotNull(message = "MSG01") ReportReason reason,
        @Size(max = 500, message = "MSG01") String description) {

    public ReportRequest {
        description = description == null || description.isBlank() ? null : description.strip();
    }
}
