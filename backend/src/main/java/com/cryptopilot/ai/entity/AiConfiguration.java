package com.cryptopilot.ai.entity;

import com.cryptopilot.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;

/**
 * One version of the AI Assistant's configuration (AI_CONFIGURATION, UC-41): the model, the system prompt, the
 * generation settings and the disclaimer. Exactly one version is active; the assistant reads it for every question.
 * Versions are maintained by the administration console (SRS 3.11.7), so this module only reads them.
 *
 * <p>Rule: BR-51; SRS 3.9.2, 3.11.7.
 */
@Getter
@Entity
@Table(name = "ai_configuration")
@AttributeOverride(name = "id", column = @Column(name = "config_id", nullable = false, updatable = false))
public class AiConfiguration extends BaseEntity {

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    @Column(name = "system_prompt", nullable = false)
    private String systemPrompt;

    @Column(name = "temperature", nullable = false, precision = 3, scale = 2)
    private BigDecimal temperature;

    @Column(name = "max_output_tokens", nullable = false)
    private int maxOutputTokens;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "disclaimer", nullable = false)
    private String disclaimer;

    protected AiConfiguration() {}
}
