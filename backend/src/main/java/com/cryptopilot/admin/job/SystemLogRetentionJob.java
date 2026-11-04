package com.cryptopilot.admin.job;

import com.cryptopilot.admin.service.SystemMonitorService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes the system log rows last seen more than 90 days ago, daily at 01:00 UTC with the other NSF-17 clean-ups.
 * The audit trail is not touched: what people did is kept as BR-57 says, what failed only as long as it helps to find
 * out why.
 *
 * <p>Rule: NSF-17; UC-52.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SystemLogRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(SystemLogRetentionJob.class);

    private final SystemMonitorService monitor;

    @Scheduled(cron = "0 0 1 * * *", zone = "UTC")
    public void purge() {
        int deleted = monitor.deleteExpired();
        if (deleted > 0) {
            log.info("Deleted {} system log rows not seen for 90 days", deleted);
        }
    }
}
