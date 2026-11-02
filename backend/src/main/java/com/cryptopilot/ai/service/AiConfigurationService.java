package com.cryptopilot.ai.service;

import com.cryptopilot.ai.dto.request.AiConfigurationRequest;
import com.cryptopilot.ai.dto.response.AiConfigurationResponse;
import java.util.List;
import java.util.UUID;

/**
 * The versions of the AI configuration in the administration console (SCR-42, UC-50). Implemented by
 * {@link com.cryptopilot.ai.service.impl.AiConfigurationServiceImpl}.
 *
 * <p>Rule: UC-50; BR-57; SRS 3.11.7.
 */
public interface AiConfigurationService {

    /** Every version, the newest first. */
    List<AiConfigurationResponse> list();

    /** Adds an inactive version; audited. */
    AiConfigurationResponse create(UUID adminId, AiConfigurationRequest request);

    /**
     * Makes the version the active one and deactivates the previous one, in one transaction; audited. Activating the
     * active version changes nothing.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when there is no such version (MSG41)
     */
    AiConfigurationResponse activate(UUID adminId, UUID configurationId);
}
