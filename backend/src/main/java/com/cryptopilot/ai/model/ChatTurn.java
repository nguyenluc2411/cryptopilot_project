package com.cryptopilot.ai.model;

import com.cryptopilot.ai.model.enums.SenderRole;
import java.util.Objects;

/** One earlier turn sent with a question (TECHNICAL_DESIGN 7.14 step 2). */
public record ChatTurn(SenderRole role, String text) {

    public ChatTurn {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
    }
}
