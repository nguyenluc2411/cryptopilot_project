package com.cryptopilot.ai.entity;

import com.cryptopilot.ai.model.AiConfigurationSettings;
import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.common.util.UuidV7;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * One version of the AI Assistant's configuration (AI_CONFIGURATION, UC-50): the model, the system prompt, the
 * generation settings and the disclaimer. A version is never edited: the administrator adds a new one and activates
 * it, so the old ones stay as history. Exactly one version is active; the assistant reads it for every question.
 *
 * <p>Rule: BR-51; UC-50; SRS 3.9.2, 3.11.7.
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

    private AiConfiguration(UUID id, AiConfigurationSettings settings, UUID adminId) {
        super(id);
        this.modelName = settings.modelName();
        this.systemPrompt = settings.systemPrompt();
        this.temperature = settings.temperature();
        this.maxOutputTokens = settings.maxOutputTokens();
        this.disclaimer = settings.disclaimer();
        this.updatedBy = adminId;
        this.active = false;
    }

    /** A new inactive version created by an administrator. */
    public static AiConfiguration create(AiConfigurationSettings settings, UUID adminId) {
        Objects.requireNonNull(settings, "settings");
        return new AiConfiguration(UuidV7.next(), settings, adminId);
    }

    /** What the version holds. */
    public AiConfigurationSettings settings() {
        return new AiConfigurationSettings(modelName, systemPrompt, temperature, maxOutputTokens, disclaimer);
    }

    /** Makes the version the one the assistant uses; the caller deactivates the previous one first. */
    public void activate(UUID adminId) {
        this.active = true;
        this.updatedBy = adminId;
    }

    /** Takes the version out of use when another is activated. */
    public void deactivate(UUID adminId) {
        this.active = false;
        this.updatedBy = adminId;
    }
}
