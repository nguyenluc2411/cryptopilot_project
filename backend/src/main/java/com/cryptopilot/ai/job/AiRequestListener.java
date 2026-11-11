package com.cryptopilot.ai.job;

import com.cryptopilot.ai.event.AiQuestionAsked;
import com.cryptopilot.ai.service.AiAnswerService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Answers each stored question (NSF-16, TECHNICAL_DESIGN 7.14 step 2): after the asking transaction commits, on its
 * own thread, outside any transaction, because the call to the AI Service may take up to the answer timeout and must
 * not hold a connection meanwhile. The service writes the outcome in its own short transactions and swallows the AI
 * Service's failures as a FAILED question, so the registry sees the event completed and does not resubmit it.
 *
 * <p>Rule: NSF-16; TECHNICAL_DESIGN 7.14.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AiRequestListener {

    private final AiAnswerService answers;

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(AiQuestionAsked asked) {
        answers.answer(asked.questionId());
    }
}
