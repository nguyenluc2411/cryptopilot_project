package com.cryptopilot.academy.repository;

import com.cryptopilot.academy.entity.AcademyArticle;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to the lesson rows an administrator writes. The public side reads them through
 * {@link AcademyCatalogRepository}.
 *
 * <p>Rule: T-114.
 */
public interface AcademyArticleRepository extends Repository<AcademyArticle, UUID> {

    @Transactional(readOnly = true)
    Optional<AcademyArticle> findById(UUID articleId);

    @Transactional(readOnly = true)
    Optional<AcademyArticle> findBySlug(String slug);

    @Transactional(readOnly = true)
    boolean existsBySlug(String slug);

    @Transactional(readOnly = true)
    boolean existsBySlugAndIdNot(String slug, UUID articleId);

    @Transactional(readOnly = true)
    boolean existsByPathOrder(Integer pathOrder);

    @Transactional(readOnly = true)
    boolean existsByPathOrderAndIdNot(Integer pathOrder, UUID articleId);

    /** Writes a lesson. Not transactional here; the unit of work is the calling service's. */
    AcademyArticle save(AcademyArticle article);

    void delete(AcademyArticle article);

    /** Sends pending statements, so the update instant is stamped before the response is built. */
    void flush();
}
