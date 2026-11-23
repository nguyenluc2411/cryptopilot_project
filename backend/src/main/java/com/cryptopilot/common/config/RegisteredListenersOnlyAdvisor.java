package com.cryptopilot.common.config;

import org.springframework.aop.Pointcut;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.annotation.AnnotationMethodMatcher;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.support.CompletionRegisteringAdvisor;

/**
 * Spring Modulith's completion advisor, narrowed to the listeners whose deliveries the event publication registry
 * records: those annotated {@link ApplicationModuleListener} ({@code spring.modulith.events.registry-trigger-annotation}).
 *
 * <p>Modulith 2.1 advises every after-commit {@code @TransactionalEventListener}, recorded or not, and marks the
 * delivery processing and completed, each in a transaction of its own, after the publisher's commit but while the
 * publisher still holds its connection. For a listener no publication is ever recorded for, those transactions find
 * nothing and only take a second connection from the pool: concurrent paper placements took the whole pool and waited
 * for its timeout (TR-32). The recorded listeners are advised exactly as before.
 *
 * <p>Rule: ADR-010; TR-32.
 */
public class RegisteredListenersOnlyAdvisor extends CompletionRegisteringAdvisor {

    private static final long serialVersionUID = 1L;

    /** The narrowed pointcut, built once on first use from Modulith's own. */
    private transient volatile Pointcut narrowed;

    public RegisteredListenersOnlyAdvisor(ObjectFactory<EventPublicationRegistry> registry) {
        super(registry::getObject);
    }

    @Override
    public Pointcut getPointcut() {
        Pointcut pointcut = narrowed;
        if (pointcut == null) {
            pointcut = new ComposablePointcut(super.getPointcut())
                    .intersection(new AnnotationMethodMatcher(ApplicationModuleListener.class, true));
            narrowed = pointcut;
        }
        return pointcut;
    }
}
