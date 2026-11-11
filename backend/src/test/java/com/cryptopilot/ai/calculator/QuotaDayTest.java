package com.cryptopilot.ai.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class QuotaDayTest {

    @Test
    void BR50_theQuotaDayStartsAtMidnightUtcPlus7() {
        // 2026-11-01 16:59:59Z is 23:59:59 in Vietnam: still November 1st there.
        assertThat(QuotaDay.start(Instant.parse("2026-11-01T16:59:59Z"))).isEqualTo("2026-10-31T17:00:00Z");
        assertThat(QuotaDay.resetsAt(Instant.parse("2026-11-01T16:59:59Z"))).isEqualTo("2026-11-01T17:00:00Z");
    }

    @Test
    void BR50_atMidnightUtcPlus7_aNewDayBegins() {
        Instant midnight = Instant.parse("2026-11-01T17:00:00Z");

        assertThat(QuotaDay.start(midnight)).isEqualTo(midnight);
        assertThat(QuotaDay.resetsAt(midnight)).isEqualTo("2026-11-02T17:00:00Z");
    }
}
