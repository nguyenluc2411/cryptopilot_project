package com.cryptopilot.market.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The rejection contract as a type: one final exception with exactly the six kinds its Javadoc tells
 * callers how to handle. A seventh kind, or a subclass that a caller would have to catch separately, is a
 * change of contract and has to fail here first.
 *
 * <p>Rule: BR-09; TECHNICAL_DESIGN 7.1.2.
 */
class BinanceClientExceptionTest {

    @Test
    void TD712_theContract_isOneFinalTypeWithSixKinds() {
        assertThat(Modifier.isFinal(BinanceClientException.class.getModifiers()))
                .isTrue();
        assertThat(BinanceClientException.Kind.values())
                .extracting(Enum::name)
                .containsExactly("RATE_LIMITED", "BANNED", "CIRCUIT_OPEN", "UNAVAILABLE", "REJECTED", "MALFORMED");
    }

    @Test
    void TD712_aRefusal_carriesItsKindVenueAndRetryInstant() {
        Instant at = Instant.parse("2026-09-24T10:01:00Z");

        BinanceClientException refusal = new BinanceClientException(
                BinanceClientException.Kind.RATE_LIMITED, BinanceVenue.USD_M_FUTURES, "paused", at, null);

        assertThat(refusal.kind()).isEqualTo(BinanceClientException.Kind.RATE_LIMITED);
        assertThat(refusal.venue()).isEqualTo(BinanceVenue.USD_M_FUTURES);
        assertThat(refusal.retryAt()).contains(at);
        assertThat(new BinanceClientException(
                                BinanceClientException.Kind.REJECTED, BinanceVenue.SPOT, "bad symbol", null, null)
                        .retryAt())
                .isEmpty();
    }
}
