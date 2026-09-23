package com.cryptopilot.common.entity;

import com.cryptopilot.common.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import org.springframework.data.domain.Persistable;

/**
 * The layer supertype of every entity that owns a row: it carries the identity and the audit
 * fields, so no table repeats them and no entity invents its own way of being equal.
 *
 * <p>The identifier is assigned at construction rather than by the database, because an event, an
 * outbox row or a log line frequently needs the id before the transaction commits (ADR-009). An
 * application-assigned key is also the reason this class implements {@link Persistable}: a
 * repository cannot tell a new entity from a detached one by looking at a key that is never null,
 * so it would issue a select before every insert. {@link #isNew()} answers that question from the
 * creation instant, which only the insert callback sets.
 *
 * <p>Identity, not state, decides equality. Two rows of the same type with the same id are the same
 * entity however many fields differ between them, which is what lets an entity be compared safely
 * before and after a change — and, unlike equality by state, does not change when a field is
 * edited, so an entity already in a {@code HashSet} stays findable. Both methods are final so that
 * a persistence proxy cannot redefine them. {@code getClass()} rather than {@code instanceof} keeps
 * two different tables with the same key from comparing equal; a Hibernate proxy is unwrapped
 * before it reaches here because nothing in this design maps a lazy association.
 *
 * <p>The audit instants are recorded through {@link #markCreated(Instant)} and
 * {@link #markUpdated(Instant)} rather than read from the system clock, so the caller supplies the
 * time from the injected {@link java.time.Clock} (BR-08) and a test can pin it. {@link
 * AuditInstantListener} is the caller for every entity lifecycle, which is why no entity anywhere
 * calls {@code Instant.now()}.
 *
 * <p>The three columns mapped here — {@code created_at}, {@code updated_at} and {@code version} —
 * carry those names on every table that has a single {@code uuid} key. The key column does not: it
 * is {@code user_id} on one table and {@code token_id} on the next, so each subclass names it with
 * an {@code @AttributeOverride}.
 *
 * <p>Rule: TECHNICAL_DESIGN sections 5.2, 5.5 and 6; ADR-009.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>.
 * Addison-Wesley, "Layer Supertype", "Identity Field" and "Optimistic Offline Lock".
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 5 (an entity is
 * defined by its identity, not by its attributes).
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditInstantListener.class)
public abstract class BaseEntity implements Persistable<UUID> {

    /** The primary key, assigned when the object is created and never changed afterwards. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UuidV7.next();

    /** When the row was first written, or {@code null} while the entity is still unsaved. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When the row was last written, or {@code null} while the entity is still unsaved. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The optimistic locking version; zero until the row has been written. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected BaseEntity() {}

    /**
     * For an entity whose key is not its own to choose: a row whose primary key is also the foreign
     * key to its aggregate root, such as a profile keyed by the account it describes. The generated
     * identifier is replaced before the entity is ever used, which is why this is a constructor and
     * not a setter — a key is assigned once or not at all.
     */
    protected BaseEntity(UUID id) {
        this.id = Objects.requireNonNull(id, "id must not be null");
    }

    /**
     * Whether this entity has never been written. True until the insert callback stamps the
     * creation instant, and false for anything read back from the database, so a repository inserts
     * a new entity without first selecting a row it knows is not there.
     */
    @Override
    public boolean isNew() {
        return createdAt == null;
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
