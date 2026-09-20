package com.cryptopilot.common.entity;

import com.cryptopilot.common.util.UuidV7;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The layer supertype of every entity that owns a row: it carries the identity and the audit
 * fields, so no table repeats them and no entity invents its own way of being equal.
 *
 * <p>The identifier is assigned at construction rather than by the database, because an event, an
 * outbox row or a log line frequently needs the id before the transaction commits (ADR-009).
 *
 * <p>Identity, not state, decides equality. Two rows of the same type with the same id are the same
 * entity however many fields differ between them, which is what lets an entity be compared safely
 * before and after a change. Both methods are final so that a persistence proxy cannot redefine
 * them.
 *
 * <p>The audit instants are recorded through {@link #markCreated(Instant)} and
 * {@link #markUpdated(Instant)} rather than read from the system clock, so the caller supplies the
 * time from the injected {@link java.time.Clock} (BR-08) and a test can pin it. T-005 maps this
 * class to JPA and calls these two methods from the entity lifecycle callbacks; until the schema
 * exists the class carries no persistence annotations.
 *
 * <p>Rule: TECHNICAL_DESIGN sections 5.2, 5.5 and 6; ADR-009.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>.
 * Addison-Wesley, "Layer Supertype" and "Identity Field".
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 5 (an entity is
 * defined by its identity, not by its attributes).
 */
public abstract class BaseEntity {

    private UUID id = UuidV7.next();

    private Instant createdAt;

    private Instant updatedAt;

    private long version;

    protected BaseEntity() {}

    /** The primary key, assigned when the object is created and never changed afterwards. */
    public UUID getId() {
        return id;
    }

    /** When the row was first written, or {@code null} while the entity is still unsaved. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** When the row was last written, or {@code null} while the entity is still unsaved. */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** The optimistic locking version; zero until the row has been written. */
    public long getVersion() {
        return version;
    }

    /**
     * Records the creation instant, taken from the injected clock. Sets both audit fields, so that
     * a row that has never been changed still reports when it was last written.
     */
    protected void markCreated(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Records a modification instant, taken from the injected clock. */
    protected void markUpdated(Instant now) {
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        return id.equals(((BaseEntity) other).id);
    }

    @Override
    public final int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + id + ")";
    }
}
