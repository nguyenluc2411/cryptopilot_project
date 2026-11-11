package com.cryptopilot.ai.service.impl;

import com.cryptopilot.admin.AdminApi;
import com.cryptopilot.admin.AuditEntry;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import com.cryptopilot.ai.dto.request.AiConfigurationRequest;
import com.cryptopilot.ai.dto.response.AiConfigurationResponse;
import com.cryptopilot.ai.entity.AiConfiguration;
import com.cryptopilot.ai.model.AiConfigurationSettings;
import com.cryptopilot.ai.repository.AiConfigurationRepository;
import com.cryptopilot.ai.service.AiConfigurationService;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the versions of the AI configuration: a new version is saved inactive and never edited; activation turns the
 * previous version off before the new one on, so {@code uq_ai_configuration_active} always holds, and both changes
 * commit together. Each change is written to the audit trail with its values.
 *
 * <p>Rule: UC-50; BR-57; NSF-18; SRS 3.11.7.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AiConfigurationServiceImpl implements AiConfigurationService {

    private final AiConfigurationRepository configurations;
    private final AdminApi admin;

    @Override
    public List<AiConfigurationResponse> list() {
        return configurations.findAllByOrderByCreatedAtDesc().stream()
                .map(AiConfigurationServiceImpl::response)
                .toList();
    }

    @Override
    @Transactional
    public AiConfigurationResponse create(UUID adminId, AiConfigurationRequest request) {
        AiConfigurationSettings settings = new AiConfigurationSettings(
                request.modelName().strip(),
                request.systemPrompt().strip(),
                request.temperature(),
                request.maxOutputTokens(),
                request.disclaimer().strip());
        AiConfiguration configuration = configurations.saveAndFlush(AiConfiguration.create(settings, adminId));
        admin.audit(new AuditEntry(
                adminId,
                AuditAction.CONFIGURATION_CREATED,
                AuditedEntity.AI_CONFIGURATION,
                configuration.getId(),
                null,
                values(settings)));
        return response(configuration);
    }

    @Override
    @Transactional
    public AiConfigurationResponse activate(UUID adminId, UUID configurationId) {
        AiConfiguration target = configurations
                .findById(configurationId)
                .orElseThrow(() -> new ResourceNotFoundException("AiConfiguration", configurationId));
        if (target.isActive()) {
            return response(target);
        }
        Optional<AiConfiguration> previous = configurations.findByActiveTrue();
        previous.ifPresent(old -> {
            old.deactivate(adminId);
            configurations.saveAndFlush(old);
        });
        target.activate(adminId);
        AiConfiguration saved = configurations.saveAndFlush(target);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("active", false);
        before.put(
                "previousActiveId", previous.map(old -> old.getId().toString()).orElse(null));
        admin.audit(new AuditEntry(
                adminId,
                AuditAction.CONFIGURATION_CHANGED,
                AuditedEntity.AI_CONFIGURATION,
                saved.getId(),
                before,
                Map.of("active", true)));
        return response(saved);
    }

    private static Map<String, Object> values(AiConfigurationSettings settings) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("modelName", settings.modelName());
        values.put("systemPrompt", settings.systemPrompt());
        values.put("temperature", settings.temperature().toPlainString());
        values.put("maxOutputTokens", settings.maxOutputTokens());
        values.put("disclaimer", settings.disclaimer());
        return values;
    }

    private static AiConfigurationResponse response(AiConfiguration configuration) {
        return new AiConfigurationResponse(
                configuration.getId(),
                configuration.getModelName(),
                configuration.getSystemPrompt(),
                configuration.getTemperature(),
                configuration.getMaxOutputTokens(),
                configuration.getDisclaimer(),
                configuration.isActive(),
                configuration.getCreatedAt(),
                configuration.getUpdatedAt(),
                configuration.getUpdatedBy());
    }
}
