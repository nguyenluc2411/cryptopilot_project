package com.cryptopilot.academy.service;

import com.cryptopilot.academy.dto.response.ArticleDetailResponse;
import com.cryptopilot.academy.dto.response.ArticleSummaryResponse;
import com.cryptopilot.academy.dto.response.TermResponse;
import com.cryptopilot.academy.dto.response.TopicResponse;
import com.cryptopilot.academy.model.ArticleFilter;
import com.cryptopilot.common.web.PageResponse;
import java.util.List;

/**
 * The public side of the Academy: what anyone, a Guest included, reads. Only published lessons are shown; any other
 * lesson answers MSG41 as if it did not exist. Implemented by
 * {@link com.cryptopilot.academy.service.impl.AcademyServiceImpl}.
 *
 * <p>Rule: D-48; T-114.
 */
public interface AcademyService {

    /** Every topic in display order. */
    List<TopicResponse> topics();

    /** One page of published lessons in reading order; MSG15 for a search text outside 2 to 100 characters. */
    PageResponse<ArticleSummaryResponse> articles(ArticleFilter filter, Integer page, Integer pageSize);

    /** The published lesson with this slug and where to read on; MSG41 when there is none. */
    ArticleDetailResponse article(String slug);

    /** The lessons of the beginner path, in order. */
    List<ArticleSummaryResponse> beginnerPath();

    /**
     * The glossary in alphabetical order.
     *
     * @param text only terms containing this, 2 to 100 characters, or {@code null}
     * @param letter only terms starting with this one letter, or {@code null}
     */
    List<TermResponse> terms(String text, String letter);
}
