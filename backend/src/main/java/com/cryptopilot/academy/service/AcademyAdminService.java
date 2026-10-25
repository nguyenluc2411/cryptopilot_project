package com.cryptopilot.academy.service;

import com.cryptopilot.academy.dto.request.ArticleRequest;
import com.cryptopilot.academy.dto.request.TermRequest;
import com.cryptopilot.academy.dto.response.AdminArticleResponse;
import com.cryptopilot.academy.dto.response.AdminArticleSummaryResponse;
import com.cryptopilot.academy.dto.response.AdminTermResponse;
import com.cryptopilot.academy.model.enums.ArticleStatus;
import com.cryptopilot.common.web.PageResponse;
import java.util.List;
import java.util.UUID;

/**
 * The Academy in the administration console: lessons in every status and the glossary, written by an administrator.
 * Every change is audited in its own transaction (BR-57). A lesson or term that does not exist answers MSG41.
 * Implemented by {@link com.cryptopilot.academy.service.impl.AcademyAdminServiceImpl}.
 *
 * <p>Rule: D-48; BR-57; T-114.
 */
public interface AcademyAdminService {

    /** One page of lessons, the latest change first; MSG15 for a search text outside 2 to 100 characters. */
    PageResponse<AdminArticleSummaryResponse> articles(
            ArticleStatus status, String topic, String q, Integer page, Integer pageSize);

    AdminArticleResponse article(UUID articleId);

    /** A new lesson, as a draft; the slug and the place on the path must be free. */
    AdminArticleResponse create(UUID adminId, ArticleRequest request);

    /** New content for a lesson; its status stays. */
    AdminArticleResponse update(UUID adminId, UUID articleId, ArticleRequest request);

    /** Shows the lesson to everyone; publishing a published lesson changes nothing. */
    AdminArticleResponse publish(UUID adminId, UUID articleId);

    /** Takes the lesson down; hiding a hidden lesson changes nothing. */
    AdminArticleResponse hide(UUID adminId, UUID articleId);

    /** Deletes a lesson that is not published, with its readers' marks; its terms lose their link. */
    void delete(UUID adminId, UUID articleId);

    /** The glossary in alphabetical order; MSG15 for a search text outside 2 to 100 characters. */
    List<AdminTermResponse> terms(String q);

    AdminTermResponse createTerm(UUID adminId, TermRequest request);

    AdminTermResponse updateTerm(UUID adminId, UUID termId, TermRequest request);

    void deleteTerm(UUID adminId, UUID termId);
}
