package com.cryptopilot.watchlist.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WatchlistTest {

    private static final Instant ADDED = Instant.parse("2026-10-01T08:00:00Z");

    @Test
    void UC12_aNewRow_keepsItsOwnerPairPositionAndTrimmedTexts() {
        UUID user = UUID.randomUUID();
        UUID pair = UUID.randomUUID();

        Watchlist row = Watchlist.add(user, pair, "  Majors ", " breakout ", 3, ADDED);

        assertThat(row.getUserId()).isEqualTo(user);
        assertThat(row.getPairId()).isEqualTo(pair);
        assertThat(row.getLabel()).isEqualTo("Majors");
        assertThat(row.getNote()).isEqualTo("breakout");
        assertThat(row.getSortOrder()).isEqualTo(3);
        assertThat(row.getAddedAt()).isEqualTo(ADDED);
    }

    @Test
    void UC12_aBlankLabelOrNote_removesIt() {
        Watchlist row = Watchlist.add(UUID.randomUUID(), UUID.randomUUID(), "Majors", "note", 0, ADDED);

        row.relabel(" ");
        row.annotate("");

        assertThat(row.getLabel()).isNull();
        assertThat(row.getNote()).isNull();
    }

    @Test
    void UC12_aRowMoves_butNeverToANegativePosition() {
        Watchlist row = Watchlist.add(UUID.randomUUID(), UUID.randomUUID(), null, null, 0, ADDED);

        row.moveTo(7);

        assertThat(row.getSortOrder()).isEqualTo(7);
        assertThatThrownBy(() -> row.moveTo(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Watchlist.add(UUID.randomUUID(), UUID.randomUUID(), null, null, -1, ADDED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
