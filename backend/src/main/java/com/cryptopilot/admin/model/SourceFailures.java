package com.cryptopilot.admin.model;

import com.cryptopilot.admin.model.enums.SystemEventSource;
import java.time.Instant;

/**
 * The failures of one source as the health panel reads them.
 *
 * @param lastFailureAt when it last failed
 * @param failuresSince how many failures were seen since the start of the counting window
 * @param lastMessage the message of the latest failure, or {@code null}
 *     <p>Rule: UC-52.
 */
public record SourceFailures(SystemEventSource source, Instant lastFailureAt, long failuresSince, String lastMessage) {}
