package com.cryptopilot.news.service;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.news.dto.request.NewsSourceRequest;
import com.cryptopilot.news.dto.response.NewsCrawlLogResponse;
import com.cryptopilot.news.dto.response.NewsSourceResponse;
import java.util.List;
import java.util.UUID;

/**
 * The administration of news sources and their crawl log (SCR-41, UC-49). Every change is audited with its old and
 * new values (BR-57). Implemented by {@link com.cryptopilot.news.service.impl.NewsSourceServiceImpl}.
 *
 * <p>Rule: UC-49; BR-49, BR-57; NSF-10.
 */
public interface NewsSourceService {

    /** Every source, by name, each with its latest crawl. */
    List<NewsSourceResponse> list();

    /** One source; MSG41 when unknown. */
    NewsSourceResponse get(UUID sourceId);

    /**
     * Adds a source after reading its feed once (UC-49).
     *
     * @throws com.cryptopilot.common.exception.FieldValidationException naming each rejected field: the feed address
     *     is not a web address, a selector does not parse, or ({@code NEWS_FEED_UNREADABLE}) the feed cannot be read
     * @throws com.cryptopilot.common.exception.BusinessException {@code NEWS_SOURCE_ALREADY_REGISTERED}
     */
    NewsSourceResponse create(UUID adminId, NewsSourceRequest request);

    /**
     * Replaces every setting of a source (UC-49). The feed is read again when its address or the agent sent to it
     * changes; when it was last read is kept.
     *
     * @throws com.cryptopilot.common.exception.FieldValidationException as {@link #create}
     * @throws com.cryptopilot.common.exception.BusinessException {@code NEWS_SOURCE_ALREADY_REGISTERED}
     */
    NewsSourceResponse update(UUID adminId, UUID sourceId, NewsSourceRequest request);

    /**
     * Deletes a source no article came from, with its crawl log.
     *
     * @throws com.cryptopilot.common.exception.BusinessException {@code NEWS_SOURCE_IN_USE}: switch it off instead
     */
    void delete(UUID adminId, UUID sourceId);

    /** One page of a source's crawl log, the latest first (UC-49); MSG41 when the source is unknown. */
    PageResponse<NewsCrawlLogResponse> crawlLogs(UUID sourceId, Integer page, Integer pageSize);

    /**
     * Crawls a source now, active or not (T-080b); see {@link CrawlService#crawlNow}.
     *
     * @return the crawl log entry of this crawl
     */
    NewsCrawlLogResponse crawlNow(UUID sourceId);
}
