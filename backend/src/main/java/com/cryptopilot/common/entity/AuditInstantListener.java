package com.cryptopilot.common.entity;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Stamps {@code created_at} and {@code updated_at} on every entity, from the injected clock.
 *
 * <p>The two columns are {@code NOT NULL} on every table and carry no database default, which is
 * deliberate: a default would be the server's wall clock, and a test could then neither pin the
 * value nor assert it. This listener is what fills them instead, and it is the only place in the
 * application that reads the current time on behalf of an entity — which is what lets the rule "no
 * entity calls {@code Instant.now()}" hold without every service remembering to stamp its rows.
 *
 * <p>It is a Spring bean, so Hibernate resolves it through Spring's bean container and its clock is
 * the same {@link Clock} every other component injects. A test that needs a fixed instant replaces
 * that one bean and every entity written in the test carries the frozen time.
 *
 * <p>Rule: TECHNICAL_DESIGN sections 5.2 and 6 (audit instants are written from the injected clock,
 * never by a database default).
 *
 * <p>Reference: Freeman, S. and Pryce, N. (2009). <i>Growing Object-Oriented Software, Guided by
 * Tests</i>. Addison-Wesley, ch. 6 (the system clock is an explicit collaborator, not an ambient
 * call).
 */
@Component
public class AuditInstantListener {

    private final Clock clock;

    AuditInstantListener(Clock clock) {
        this.clock = clock;
    }

    @PrePersist
    void onPersist(BaseEntity entity) {
        entity.markCreated(clock.instant());
    }

    @PreUpdate
    void onUpdate(BaseEntity entity) {
        entity.markUpdated(clock.instant());
    }
}
