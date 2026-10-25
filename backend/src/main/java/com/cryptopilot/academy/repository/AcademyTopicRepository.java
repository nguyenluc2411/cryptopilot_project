package com.cryptopilot.academy.repository;

import com.cryptopilot.academy.entity.AcademyTopic;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to the topic rows an administrator writes.
 *
 * <p>Rule: T-114.
 */
public interface AcademyTopicRepository extends Repository<AcademyTopic, UUID> {

    @Transactional(readOnly = true)
    Optional<AcademyTopic> findById(UUID topicId);

    @Transactional(readOnly = true)
    boolean existsBySlug(String slug);

    @Transactional(readOnly = true)
    boolean existsBySlugAndIdNot(String slug, UUID topicId);

    /** Writes a topic. Not transactional here; the unit of work is the calling service's. */
    AcademyTopic save(AcademyTopic topic);

    void delete(AcademyTopic topic);

    /** Sends pending statements, so the new version is known before the response is built. */
    void flush();
}
