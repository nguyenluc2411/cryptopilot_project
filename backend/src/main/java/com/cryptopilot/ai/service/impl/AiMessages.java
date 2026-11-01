package com.cryptopilot.ai.service.impl;

import com.cryptopilot.ai.dto.response.ConversationResponse;
import com.cryptopilot.ai.dto.response.MessageResponse;
import com.cryptopilot.ai.entity.AiConversation;
import com.cryptopilot.ai.entity.AiMessage;

/** The responses the AI services build from their rows. */
final class AiMessages {

    private AiMessages() {}

    static MessageResponse of(AiMessage message) {
        return new MessageResponse(
                message.getId(),
                message.getConversationId(),
                message.getSenderRole(),
                message.getContent(),
                message.getStatus(),
                message.getCreatedAt());
    }

    static ConversationResponse of(AiConversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getPairId(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }
}
