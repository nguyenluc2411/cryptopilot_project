package com.cryptopilot.community.service;

import com.cryptopilot.community.dto.request.ReportRequest;
import com.cryptopilot.community.dto.response.ReportResponse;
import java.util.UUID;

/**
 * Violation reports of posts (UC-33). Implemented by {@link com.cryptopilot.community.service.impl.ReportServiceImpl}.
 *
 * <p>Rule: UC-33; BR-46, BR-47; D-48.
 */
public interface ReportService {

    /**
     * Files an OPEN report of a post the caller may see and did not write.
     *
     * @throws com.cryptopilot.common.exception.BusinessException MSG41 for a post the caller may not see, MSG34
     *     ({@code POST_ALREADY_REPORTED}) when the caller already reported it, {@code POST_REPORT_OWN_POST} for the
     *     caller's own post, MSG01 for OTHER without a description
     */
    ReportResponse report(UUID caller, UUID postId, ReportRequest request);

    /**
     * Files an OPEN report of a published comment, of a post the caller may see, that the caller did not write
     * (D-125). The comment's text is kept with the report.
     *
     * @throws com.cryptopilot.common.exception.BusinessException MSG41 for a comment the caller may not see, MSG34
     *     ({@code COMMENT_ALREADY_REPORTED}) when the caller already reported it, {@code COMMENT_REPORT_OWN_COMMENT}
     *     for the caller's own comment, MSG01 for OTHER without a description
     */
    ReportResponse reportComment(UUID caller, UUID commentId, ReportRequest request);
}
