package com.cryptopilot.news.service;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.news.dto.response.AdminNewsArticleResponse;
import java.util.UUID;

/**
 * The news articles in the administration console (T-080b): every article with its status, hiding one from the feed
 * and showing it again (audited, BR-57), and asking NSF-11 for its summary now.
 *
 * <p>Rule: NSF-11; BR-57; D-128.
 */
public interface NewsArticleAdminService {

    /**
     * One page of the articles, newest first, hidden ones included.
     *
     * @param status {@code PENDING}, {@code FAILED}, {@code SUMMARIZED} or {@code HIDDEN}, in any case, or {@code null}
     *     for all
     * @param sourceId only the articles of this source, or {@code null}
     * @throws com.cryptopilot.common.exception.FieldValidationException naming {@code status}, {@code page} or
     *     {@code pageSize} with MSG15
     */
    PageResponse<AdminNewsArticleResponse> list(String status, UUID sourceId, Integer page, Integer pageSize);

    /** Hides the article from the feed and the summary bar; nothing changes, and nothing is audited, if it is hidden. */
    AdminNewsArticleResponse hide(UUID adminId, UUID articleId);

    /** Shows a hidden article again; nothing changes, and nothing is audited, if it is shown. */
    AdminNewsArticleResponse restore(UUID adminId, UUID articleId);

    /**
     * Asks NSF-11 for the article's summary now, replacing the one it has; the answer shows in the article's status and
     * error. See {@link NewsSummaryService#summarizeNow}.
     */
    AdminNewsArticleResponse summarize(UUID articleId);
}
