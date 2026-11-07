package com.cryptopilot.ai.dto.response;

import com.cryptopilot.ai.model.enums.ConversationTopic;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of the conversation list of SCR-29.
 *
 * @param id the conversation
 * @param title its title, or {@code null} until its first question
 * @param topic GENERAL, MARKET or MY_TRADING
 * @param pairId the pair it is about, or {@code null}
 * @param createdAt when it was started
 * @param updatedAt when it was last asked or renamed; the list is newest first by it
 */
public record ConversationResponse(
        UUID id, String title, ConversationTopic topic, UUID pairId, Instant createdAt, Instant updatedAt) {}
