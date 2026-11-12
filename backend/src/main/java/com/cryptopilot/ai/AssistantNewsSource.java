package com.cryptopilot.ai;

import java.util.UUID;

/**
 * The news the AI Assistant reads before it answers a question about a pair or a plan (T-074): what the outlets the
 * administrators chose have published lately about the pair's base coin, and about the crypto market in general.
 *
 * <p>Declared here and implemented by the {@code news} module, which already depends on this one for its summaries;
 * the assistant reading news through its own port keeps the dependency one way.
 *
 * <p>Rule: BR-51, NSF-10, NSF-11; TECHNICAL_DESIGN 7.14 step 1.
 */
public interface AssistantNewsSource {

    /**
     * The recent articles about the base coin of {@code pairId} and about the market; empty lists when there are none.
     * Hidden articles are never included.
     */
    AssistantNews recentNews(UUID pairId);
}
