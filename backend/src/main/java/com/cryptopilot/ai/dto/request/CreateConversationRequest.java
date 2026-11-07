package com.cryptopilot.ai.dto.request;

import com.cryptopilot.ai.model.enums.ConversationTopic;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * "New conversation" on SCR-29, or "Ask AI" on SCR-10, SCR-11 or SCR-18 (SRS 3.9.1).
 *
 * @param topic GENERAL, MARKET or MY_TRADING, or {@code null} for GENERAL; it never changes afterwards
 * @param pairId the pair the conversation is about, or {@code null}; never on MY_TRADING (a plan names its own pair)
 * @param title up to 100 characters, or {@code null} to name it after its first question
 */
public record CreateConversationRequest(
        ConversationTopic topic,
        UUID pairId,
        @Size(max = 100, message = "MSG01") String title) {

    /** The topic asked for, GENERAL when none. */
    public ConversationTopic topicOrGeneral() {
        return topic == null ? ConversationTopic.GENERAL : topic;
    }
}
