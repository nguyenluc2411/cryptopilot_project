package com.cryptopilot.academy.repository;

import com.cryptopilot.academy.entity.AcademyTerm;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to the glossary rows an administrator writes.
 *
 * <p>Rule: T-114.
 */
public interface AcademyTermRepository extends Repository<AcademyTerm, UUID> {

    @Transactional(readOnly = true)
    Optional<AcademyTerm> findById(UUID termId);

    @Transactional(readOnly = true)
    boolean existsBySlug(String slug);

    @Transactional(readOnly = true)
    boolean existsBySlugAndIdNot(String slug, UUID termId);

    /** Writes a term. Not transactional here; the unit of work is the calling service's. */
    AcademyTerm save(AcademyTerm term);

    void delete(AcademyTerm term);
}
