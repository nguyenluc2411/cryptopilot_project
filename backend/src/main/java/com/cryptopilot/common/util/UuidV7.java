package com.cryptopilot.common.util;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import java.util.UUID;

/**
 * Generates the UUID version 7 primary keys used by every entity.
 *
 * <p>Version 7 puts a 48-bit Unix millisecond timestamp in the leading bits, so freshly generated
 * ids are close together in sort order. That keeps inserts at the right-hand edge of the B-tree
 * index on the tables that grow to millions of rows (candles, indicators, journal records), which
 * random version 4 ids do not. Ids drawn within the same millisecond stay ordered too: the
 * generator increments the random tail of the previous value instead of drawing a new one.
 *
 * <p>The generator is shared and safe to call from several threads, which matters because ids are
 * produced by the stream workers and the matching engine as well as by request threads.
 *
 * <p>Rule: ADR-009 (application-generated UUID v7 primary keys); TECHNICAL_DESIGN section 6.
 *
 * <p>Reference: Davis, K., Peabody, B. and Leach, P. (2024). RFC 9562, <i>Universally Unique
 * IDentifiers (UUIDs)</i>, section 5.7 (UUID version 7) and section 6.2 (monotonicity and
 * counters).
 */
public final class UuidV7 {

    private static final NoArgGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    private UuidV7() {}

    /** Returns the next identifier; never {@code null}, never equal to a previous one. */
    public static UUID next() {
        return GENERATOR.generate();
    }
}
