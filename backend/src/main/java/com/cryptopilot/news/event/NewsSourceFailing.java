package com.cryptopilot.news.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A news source has failed as many crawls in a row as the notice threshold (NSF-10: five), published once per run of
 * failures, in the transaction that records the last of them. The administrators are told by the notification module.
 *
 * @param sourceId the source
 * @param sourceName its name, as the administrator set it
 * @param consecutiveFailures how many crawls in a row failed
 * @param crawlLogId the crawl log entry of the failure that reached the threshold
 * @param error why the last crawl failed
 * @param failedAt when the last crawl started
 */
public record NewsSourceFailing(
        UUID sourceId, String sourceName, int consecutiveFailures, UUID crawlLogId, String error, Instant failedAt) {}
