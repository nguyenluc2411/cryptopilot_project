package com.cryptopilot.ai.repository;

import com.cryptopilot.ai.entity.AiConfiguration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The configuration versions; {@code uq_ai_configuration_active} keeps at most one active. */
public interface AiConfigurationRepository extends Repository<AiConfiguration, UUID> {

    @Transactional(readOnly = true)
    Optional<AiConfiguration> findByActiveTrue();
}
