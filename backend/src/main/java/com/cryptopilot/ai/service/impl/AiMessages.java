package com.cryptopilot.ai.service.impl;

import com.cryptopilot.ai.dto.response.ConversationResponse;
import com.cryptopilot.ai.dto.response.MessageResponse;
import com.cryptopilot.ai.dto.response.MessageSourceResponse;
import com.cryptopilot.ai.entity.AiConversation;
import com.cryptopilot.ai.entity.AiMessage;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The responses the AI services build from their rows. */
final class AiMessages {

    /** Reads the stored sources only; nothing is written with it. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AiMessages() {}

    static MessageResponse of(AiMessage message) {
        return new MessageResponse(
                message.getId(),
                message.getConversationId(),
                message.getSenderRole(),
                message.getContent(),
                message.getStatus(),
                message.getCreatedAt(),
                sources(message.getSources()));
    }

    static ConversationResponse of(AiConversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getTopic(),
                conversation.getPairId(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }

    /** The cited articles stored on an answer (T-074); none when there are none or the column cannot be read. */
    static List<MessageSourceResponse> sources(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        JsonNode array;
        try {
            array = JSON.readTree(stored);
        } catch (JacksonException e) {
            return List.of();
        }
        List<MessageSourceResponse> sources = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JsonNode source = array.get(i);
            String url = text(source, "url");
            if (url == null) {
                continue;
            }
            sources.add(new MessageSourceResponse(
                    source.path("ref").asInt(),
                    text(source, "origin"),
                    text(source, "headline"),
                    text(source, "outlet"),
                    instant(text(source, "publishedAt")),
                    url));
        }
        return List.copyOf(sources);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }

    private static Instant instant(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
