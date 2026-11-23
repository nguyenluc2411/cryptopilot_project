package com.cryptopilot.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.notification.job.AlertNotificationListener;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.watchlist.job.AlertEngine;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.support.CompletionRegisteringAdvisor;

/**
 * TR-32: Modulith's completion advice reaches the listeners the event publication registry records, and only those.
 *
 * <p>Invariants: (1) the advisor bean is the narrowed one; (2) an {@code @ApplicationModuleListener} is still advised,
 * so its publication is completed; (3) a plain after-commit {@code @TransactionalEventListener} is not advised, so its
 * delivery takes no transaction and no connection of its own.
 *
 * <p>Rule: ADR-010; TR-32.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class EventListenerAdviceTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AlertNotificationListener recorded;

    @Autowired
    private AlertEngine notRecorded;

    @Test
    void TR32_theCompletionAdvisor_isTheNarrowedOne() {
        assertThat(context.getBean(EventListenerAdviceConfig.ADVISOR))
                .isInstanceOf(RegisteredListenersOnlyAdvisor.class);
    }

    @Test
    void TR32_aRecordedListener_isStillAdvised_andAPlainAfterCommitListener_isNot() {
        assertThat(completionAdvised(recorded)).as("@ApplicationModuleListener").isTrue();
        assertThat(completionAdvised(notRecorded))
                .as("@TransactionalEventListener of the alert engine")
                .isFalse();
    }

    private static boolean completionAdvised(Object bean) {
        if (!(bean instanceof Advised advised)) {
            return false;
        }
        return Arrays.stream(advised.getAdvisors())
                .map(Advisor.class::cast)
                .anyMatch(advisor -> advisor instanceof CompletionRegisteringAdvisor);
    }
}
