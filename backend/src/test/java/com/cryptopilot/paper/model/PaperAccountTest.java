package com.cryptopilot.paper.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A paper account is its Trader's, opened at an instant: it has no key of its own (D-117).
 *
 * <p>Rule: TR-04; D-117.
 */
class PaperAccountTest {

    @Test
    void anAccount_isItsTradersAndOpenedAtAnInstant() {
        UUID trader = UUID.randomUUID();
        Instant opened = Instant.parse("2026-10-24T08:00:00Z");

        PaperAccount account = new PaperAccount(trader, opened);

        assertThat(account.userId()).isEqualTo(trader);
        assertThat(account.openedAt()).isEqualTo(opened);
    }

    @Test
    void anAccount_hasAnOwnerAndAnOpening() {
        assertThatNullPointerException().isThrownBy(() -> new PaperAccount(null, Instant.EPOCH));
        assertThatNullPointerException().isThrownBy(() -> new PaperAccount(UUID.randomUUID(), null));
    }
}
