package com.cryptopilot.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PairListingTest {

    @Test
    void BR07_aPairIsListedWhenEnabledOnEitherMarket() {
        UUID id = UUID.randomUUID();

        assertThat(new PairListing(id, "BTCUSDT", true, false).listed()).isTrue();
        assertThat(new PairListing(id, "BTCUSDT", false, true).listed()).isTrue();
        assertThat(new PairListing(id, "BTCUSDT", false, false).listed()).isFalse();
    }
}
