package com.cryptopilot.ai.job;

import com.cryptopilot.ai.config.AiAssistantProperties;
import com.cryptopilot.ai.service.AiAnswerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Fails the questions left pending past the answer timeout (SRS 3.9.1: after 60 seconds the message becomes FAILED,
 * MSG31, no quota used): a call that hangs, an event that was lost with a restart. Runs every {@code sweepInterval}.
 *
 * <p>Rule: NSF-16, BR-50; SRS 3.9.1.
 */
@Component
public class AiUnansweredQuestionJob {

    private static final Logger log = LoggerFactory.getLogger(AiUnansweredQuestionJob.class);

    private final AiAnswerService answers;
    private final TaskScheduler scheduler;
    private final AiAssistantProperties properties;

    public AiUnansweredQuestionJob(AiAnswerService answers, TaskScheduler scheduler, AiAssistantProperties properties) {
        this.answers = answers;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.sweepEnabled()) {
            log.info("NSF-16 unanswered question sweep is disabled");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::run, properties.sweepInterval());
        log.info(
                "NSF-16 unanswered question sweep scheduled every {}, timeout {}",
                properties.sweepInterval(),
                properties.answerTimeout());
    }

    public void run() {
        try {
            answers.failUnanswered();
        } catch (RuntimeException failure) {
            log.error("NSF-16 unanswered question sweep failed; the next run retries", failure);
        }
    }
}
