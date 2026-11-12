package com.cryptopilot.ai.repository;

import com.cryptopilot.ai.entity.AiConfiguration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The configuration versions; {@code uq_ai_configuration_active} keeps at most one active. */
public interface AiConfigurationRepository extends Repository<AiConfiguration, UUID> {

    @Transactional(readOnly = true)
    Optional<AiConfiguration> findByActiveTrue();

    @Transactional(readOnly = true)
    Optional<AiConfiguration> findById(UUID id);

    /** Every version, the newest first (SCR-42). */
    @Transactional(readOnly = true)
    List<AiConfiguration> findAllByOrderByCreatedAtDesc();

    /** Writes the version at once, so the single-active index is checked in order within the transaction. */
    AiConfiguration saveAndFlush(AiConfiguration configuration);
}
