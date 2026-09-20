package com.cryptopilot.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Checks the two properties the rest of the system relies on: an identifier is unique, and
 * identifiers sort in the order they were generated. The second one is the reason for choosing
 * version 7 at all and the one that would break silently, so it is asserted over a run long enough
 * that many values share a millisecond, which is exactly the case a naive version 7 gets wrong.
 */
class UuidV7Test {

    private static final int RUN_LENGTH = 10_000;

    @Test
    void next_returnsVersion7AndTheRfcVariant() {
        UUID id = UuidV7.next();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void consecutiveValues_sortInGenerationOrder() {
        List<UUID> generated = generate(RUN_LENGTH);

        for (int i = 1; i < generated.size(); i++) {
            assertThat(generated.get(i).compareTo(generated.get(i - 1)))
                    .as("identifier %d must sort after identifier %d", i, i - 1)
                    .isPositive();
        }
    }

    @Test
    void manyValuesShareOneMillisecond_whichIsTheCaseOrderingHasToSurvive() {
        List<UUID> generated = generate(RUN_LENGTH);

        long pairsInTheSameMillisecond = 0;
        for (int i = 1; i < generated.size(); i++) {
            if (timestampOf(generated.get(i)).equals(timestampOf(generated.get(i - 1)))) {
                pairsInTheSameMillisecond++;
            }
        }

        assertThat(pairsInTheSameMillisecond).isPositive();
    }

    @Test
    void manyValues_areAllDistinct() {
        Set<UUID> distinct = new HashSet<>(generate(RUN_LENGTH));

        assertThat(distinct).hasSize(RUN_LENGTH);
    }

    @Test
    void leadingBits_carryTheGenerationTime() {
        Instant before = Instant.now();
        UUID id = UuidV7.next();
        Instant after = Instant.now();

        assertThat(timestampOf(id)).isBetween(before.minusMillis(1), after.plusMillis(1));
    }

    private static List<UUID> generate(int count) {
        List<UUID> generated = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            generated.add(UuidV7.next());
        }
        return generated;
    }

    /** The first 48 bits of a version 7 identifier are the Unix time in milliseconds. */
    private static Instant timestampOf(UUID id) {
        return Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16);
    }
}
