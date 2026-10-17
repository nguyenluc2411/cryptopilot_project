package com.cryptopilot.common.service;

/**
 * Keeps the transactional outbox moving: retries the deliveries that failed and removes what can no longer be
 * delivered (T-056, ADR-010).
 */
public interface OutboxService {

    /**
     * Counts crashed deliveries as failed, then resubmits every failed one whose next retry is due, and reports once
     * each delivery that has run out of retries.
     *
     * @return how many publications were due for a retry
     */
    int retryFailedDeliveries();

    /**
     * Deletes the publications older than the retention.
     *
     * @return how many were deleted
     */
    int deleteExpired();
}
