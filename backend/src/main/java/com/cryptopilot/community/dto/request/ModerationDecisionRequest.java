package com.cryptopilot.community.dto.request;

import com.cryptopilot.community.model.enums.ResolutionAction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A moderator's decision on the reports of a post (SCR-36, UC-43).
 *
 * <p>Rule: UC-43; SRS 3.11.3.
 *
 * @param action HIDE_POST or REMOVE_POST for a post, HIDE_COMMENT or REMOVE_COMMENT for a comment (D-99), or DISMISS,
 *     WARN_USER or BAN_USER for either
 * @param note why, at most 500 characters, kept in the audit log; {@code null} for none
 */
public record ModerationDecisionRequest(
        @NotNull(message = "MSG01") ResolutionAction action,
        @Size(max = 500, message = "MSG01") String note) {

    public ModerationDecisionRequest {
        note = note == null || note.isBlank() ? null : note.strip();
    }
}
