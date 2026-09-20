package com.cryptopilot.common.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BaseEntityTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-20T00:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-20T08:30:00Z");

    private final Clock clock = Clock.fixed(CREATED_AT, ZoneOffset.UTC);

    @Test
    void entity_hasAnIdentifierBeforeItIsEverSaved() {
        StubEntity entity = new StubEntity();

        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getId().version()).isEqualTo(7);
    }

    @Test
    void twoEntities_getDifferentIdentifiers() {
        assertThat(new StubEntity().getId()).isNotEqualTo(new StubEntity().getId());
    }

    @Test
    void unsavedEntity_hasNoAuditInstantsAndVersionZero() {
        StubEntity entity = new StubEntity();

        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
        assertThat(entity.getVersion()).isZero();
    }

    @Test
    void creation_stampsBothInstantsFromTheInjectedClock() {
        StubEntity entity = new StubEntity();

        entity.create(clock.instant());

        assertThat(entity.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(entity.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void modification_movesOnlyTheUpdatedInstant() {
        StubEntity entity = new StubEntity();
        entity.create(CREATED_AT);

        entity.update(UPDATED_AT);

        assertThat(entity.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(entity.getUpdatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    void missingInstant_isRejected() {
        StubEntity entity = new StubEntity();

        assertThatNullPointerException().isThrownBy(() -> entity.create(null));
        assertThatNullPointerException().isThrownBy(() -> entity.update(null));
    }

    @Test
    void identityDecidesEquality_notTheOtherFields() {
        StubEntity loadedOnce = new StubEntity();
        StubEntity loadedAgain = new StubEntity();
        forceSameRow(loadedAgain, loadedOnce.getId());
        loadedAgain.update(UPDATED_AT);

        assertThat(loadedOnce).isEqualTo(loadedAgain).hasSameHashCodeAs(loadedAgain);
    }

    @Test
    void sameIdentifierOnADifferentType_isADifferentEntity() {
        StubEntity entity = new StubEntity();
        OtherStubEntity otherType = new OtherStubEntity();
        forceSameRow(otherType, entity.getId());

        assertThat(entity).isNotEqualTo(otherType);
    }

    @Test
    void entity_equalsItself_andNothingThatIsNotAnEntity() {
        StubEntity entity = new StubEntity();

        assertThat(entity).isEqualTo(entity).isNotEqualTo(null).isNotEqualTo("not an entity");
    }

    @Test
    void toString_namesTheTypeAndTheIdentifier() {
        StubEntity entity = new StubEntity();

        assertThat(entity).hasToString("StubEntity(" + entity.getId() + ")");
    }

    /** The smallest possible subclass: it only opens the protected audit hooks to the test. */
    private static class StubEntity extends BaseEntity {

        void create(Instant now) {
            markCreated(now);
        }

        void update(Instant now) {
            markUpdated(now);
        }
    }

    /** A second type, to prove that equality is not decided by the identifier alone. */
    private static final class OtherStubEntity extends BaseEntity {}

    /**
     * Makes an entity stand for the same row as another one, the way a second load of the same row
     * would. Only persistence assigns an existing identifier, so the test reaches for the field the
     * same way the persistence provider does.
     */
    private static void forceSameRow(BaseEntity entity, UUID id) {
        try {
            Field field = BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("BaseEntity no longer has an id field", e);
        }
    }
}
