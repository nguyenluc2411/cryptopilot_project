package com.cryptopilot.ai.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.ai.model.enums.ConversationTopic;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A conversation's title and soft delete (SRS 3.9.1, BR-50). */
class AiConversationTest {

    private final AiConversation conversation =
            AiConversation.start(UUID.randomUUID(), ConversationTopic.GENERAL, null, null);

    @Test
    void SRS391_deletingTwice_keepsTheFirstMoment() {
        Instant first = Instant.parse("2026-11-07T10:00:00Z");
        assertThat(conversation.isDeleted()).isFalse();

        conversation.delete(first);
        conversation.delete(first.plusSeconds(60));

        assertThat(conversation.isDeleted()).isTrue();
        assertThat(conversation.getDeletedAt()).isEqualTo(first);
    }

    @Test
    void SRS391_aBlankTitle_isNone_andALongOneIsCutTo100Characters() {
        conversation.rename("   ");
        assertThat(conversation.getTitle()).isNull();

        conversation.rename("a".repeat(150));
        assertThat(conversation.getTitle())
                .hasSize(AiConversation.TITLE_MAX_LENGTH)
                .endsWith("…");
    }

    @Test
    void SRS391_theFirstQuestionNamesTheConversation_andLaterOnesDoNot() {
        Instant at = Instant.parse("2026-11-07T10:00:00Z");
        conversation.questionAsked("  RSI   là gì? ", at);
        conversation.questionAsked("Câu sau", at.plusSeconds(5));

        assertThat(conversation.getTitle()).isEqualTo("RSI là gì?");
    }
}
