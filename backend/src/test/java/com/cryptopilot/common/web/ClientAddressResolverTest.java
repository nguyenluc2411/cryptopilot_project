package com.cryptopilot.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The client address behind a reverse proxy: {@code X-Forwarded-For} counts only when the direct peer is a trusted
 * proxy, and is read from the right.
 *
 * <p>Rule: NSF-18; TECHNICAL_DESIGN 5.3.
 */
class ClientAddressResolverTest {

    private static final String PROXY = "10.0.0.5";

    private final ClientAddressResolver resolver = new ClientAddressResolver(List.of(PROXY, "172.18.0.0/16"));

    @Test
    void TD53_aSpoofedHeaderFromAnUntrustedPeer_isIgnored() {
        assertThat(resolver.resolve("198.51.100.20", List.of("203.0.113.7"))).isEqualTo("198.51.100.20");
    }

    @Test
    void TD53_theHeaderFromATrustedProxy_givesTheClient() {
        assertThat(resolver.resolve(PROXY, List.of("203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    /**
     * Client, then a trusted proxy inside the network, then the edge proxy: the trusted hops are skipped from the
     * right. Whatever the client wrote to the left of its own address is never reached.
     */
    @Test
    void TD53_aMultiHopHeader_isResolvedFromTheRightSkippingTrustedProxies() {
        assertThat(resolver.resolve(PROXY, List.of("1.1.1.1, 203.0.113.7, 172.18.0.3")))
                .isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(PROXY, List.of("1.1.1.1", "203.0.113.7, 172.18.0.3")))
                .as("the same list split over two header lines")
                .isEqualTo("203.0.113.7");
    }

    @Test
    void TD53_whenEveryHopIsTrusted_theLeftmostIsTheClient() {
        assertThat(resolver.resolve(PROXY, List.of("172.18.0.9, 172.18.0.3"))).isEqualTo("172.18.0.9");
    }

    @Test
    void TD53_aTrustedProxyWithoutTheHeader_isItselfTheClient() {
        assertThat(resolver.resolve(PROXY, List.of())).isEqualTo(PROXY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "evil.example", "999.1.1.1", "cafe"})
    void TD53_aHopThatIsNotAnAddress_stopsTheWalkAtTheLastAddressBelievable(String junk) {
        assertThat(resolver.resolve(PROXY, List.of(junk + ", 172.18.0.3"))).isEqualTo("172.18.0.3");
    }

    @Test
    void TD53_portsAndBracketsAreRemoved() {
        assertThat(resolver.resolve(PROXY, List.of("203.0.113.7:51234"))).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(PROXY, List.of("[2001:db8::7]:443"))).isEqualTo("2001:db8::7");
    }

    @Test
    void TD53_ipv6RangesAreMatched() {
        ClientAddressResolver v6 = new ClientAddressResolver(List.of("fd00::/8"));

        assertThat(v6.resolve("fd00::1", List.of("2001:db8::7"))).isEqualTo("2001:db8::7");
        assertThat(v6.resolve("2001:db8::1", List.of("2001:db8::7"))).isEqualTo("2001:db8::1");
    }

    @Test
    void TD53_withNoTrustedProxy_theHeaderIsNeverRead() {
        ClientAddressResolver none = new ClientAddressResolver(List.of());

        assertThat(none.trustsNoProxy()).isTrue();
        assertThat(none.resolve(PROXY, List.of("203.0.113.7"))).isEqualTo(PROXY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"proxy.internal", "10.0.0.0/33", "10.0.0.0/x", "300.0.0.1", "*"})
    void TD53_aMalformedTrustedEntry_isRefused(String entry) {
        assertThatThrownBy(() -> new ClientAddressResolver(List.of(entry)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
