package com.cryptopilot.user.dto.response;

import com.cryptopilot.admin.AuditRecord;
import java.util.List;
import java.util.Map;

/**
 * One account as SCR-35 shows it: the row of the list, the profile, the counts of plans, trades and posts, and the
 * latest audit entries about it.
 *
 * <p>Rule: SRS 3.11.2; UC-42.
 *
 * @param account what the list shows
 * @param tradingStyle the profile's trading style, or {@code null}
 * @param riskProfile the profile's risk profile, or {@code null}
 * @param activity named counts, e.g. {@code plans}, {@code trades}, {@code posts}
 * @param recentAudit the latest 20 audit entries about the account, newest first
 */
public record AdminUserDetailResponse(
        AdminUserSummaryResponse account,
        String tradingStyle,
        String riskProfile,
        Map<String, Long> activity,
        List<AuditRecord> recentAudit) {}
