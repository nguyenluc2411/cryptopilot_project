package com.cryptopilot.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.ai.dto.response.MessageSourceResponse;
import com.cryptopilot.ai.entity.AiMessage;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The cited articles an answer shows (T-074): read back from {@code ai_message.sources}, none when there are none, and
 * nothing that cannot be read breaks the conversation.
 *
 * <p>Rule: T-074.
 */
class AiMessagesTest {

    @Test
    void T074_theStoredSources_areShownInTheirOrder_withTheirPublicationTime() {
        AiMessage answer = AiMessage.answer(UUID.randomUUID(), UUID.randomUUID(), "Theo [2] và [1].", """
                [{"ref":2,"origin":"SYSTEM","headline":"Sàn bị hack","outlet":"Cointelegraph",
                  "publishedAt":"2026-11-12T08:00:00Z","url":"https://ct.example/hack"},
                 {"ref":1,"origin":"SYSTEM","headline":"ETF","outlet":"CoinDesk","publishedAt":"not a time",
                  "url":"https://coindesk.example/etf"},
                 {"ref":3,"origin":"SYSTEM","headline":"No link"}]""", 10, 20);

        assertThat(AiMessages.of(answer).sources())
                .containsExactly(
                        new MessageSourceResponse(
                                2,
                                "SYSTEM",
                                "Sàn bị hack",
                                "Cointelegraph",
                                Instant.parse("2026-11-12T08:00:00Z"),
                                "https://ct.example/hack"),
                        new MessageSourceResponse(
                                1, "SYSTEM", "ETF", "CoinDesk", null, "https://coindesk.example/etf"));
    }

    @Test
    void T074_aQuestion_anAnswerCitingNothing_orUnreadableSources_showNone() {
        assertThat(AiMessages.of(AiMessage.question(UUID.randomUUID(), "BTC?", null))
                        .sources())
                .isEmpty();
        assertThat(AiMessages.of(AiMessage.answer(UUID.randomUUID(), UUID.randomUUID(), "x", 1, 1))
                        .sources())
                .isEmpty();
        assertThat(AiMessages.sources("not json")).isEmpty();
        assertThat(AiMessages.sources("  ")).isEmpty();
    }
}
