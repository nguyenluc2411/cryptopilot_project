package com.cryptopilot.common.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Finds the client's address for a request that may have come through a reverse proxy.
 *
 * <p>{@code X-Forwarded-For} is read only when the direct peer is a configured trusted proxy; from anyone else it is
 * a value the client chose. The list is walked from the right, skipping trusted proxies, and the first address that is
 * not one is the client. Entries are accepted only as IP literals (with an optional port), so nothing in the header can
 * cause a DNS lookup.
 *
 * <p>Rule: NSF-18 (the audited address); TECHNICAL_DESIGN 5.3.
 *
 * <p>Reference: Petersson, A. &amp; Nilsson, M. (2014). RFC 7239: Forwarded HTTP Extension, section 8. IETF.
 */
public final class ClientAddressResolver {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV4_WITH_PORT = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3}):\\d{1,5}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");
    private static final Pattern BRACKETED_IPV6 = Pattern.compile("\\[([0-9A-Fa-f:.]+)](?::\\d{1,5})?");

    private final List<Range> trusted;

    /**
     * @param trustedProxies addresses or CIDR ranges, e.g. {@code 10.0.0.5} or {@code 172.18.0.0/16}
     * @throws IllegalArgumentException when an entry is not an IP literal or a CIDR range
     */
    public ClientAddressResolver(List<String> trustedProxies) {
        this.trusted = trustedProxies.stream().map(Range::parse).toList();
    }

    /** Whether no proxy is trusted, so forwarded headers are never read. */
    public boolean trustsNoProxy() {
        return trusted.isEmpty();
    }

    /**
     * The client's address.
     *
     * @param remoteAddress the direct peer, as the container reports it
     * @param forwardedFor every {@code X-Forwarded-For} header value of the request, in order; may be empty
     */
    public String resolve(String remoteAddress, List<String> forwardedFor) {
        if (remoteAddress == null || !isTrusted(remoteAddress) || forwardedFor.isEmpty()) {
            return remoteAddress;
        }
        List<String> hops = new ArrayList<>();
        forwardedFor.forEach(value -> Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(hop -> !hop.isEmpty())
                .forEach(hops::add));
        String client = remoteAddress;
        for (int i = hops.size() - 1; i >= 0; i--) {
            Optional<String> hop = literal(hops.get(i));
            if (hop.isEmpty()) {
                // Not an address: nothing to the left of it can be believed.
                return client;
            }
            client = hop.get();
            if (!isTrusted(client)) {
                return client;
            }
        }
        return client;
    }

    private boolean isTrusted(String address) {
        Optional<byte[]> bytes = literal(address).flatMap(ClientAddressResolver::bytesOf);
        return bytes.isPresent() && trusted.stream().anyMatch(range -> range.contains(bytes.get()));
    }

    /** The address without brackets or port, when the value is an IP literal; empty otherwise. */
    static Optional<String> literal(String value) {
        String candidate = null;
        var withPort = IPV4_WITH_PORT.matcher(value);
        var bracketed = BRACKETED_IPV6.matcher(value);
        // IPv4 with a port first: "1.2.3.4:80" also fits the loose IPv6 character set.
        if (withPort.matches()) {
            candidate = withPort.group(1);
        } else if (bracketed.matches()) {
            candidate = bracketed.group(1);
        } else if (IPV4.matcher(value).matches() || IPV6.matcher(value).matches()) {
            candidate = value;
        }
        return candidate != null && bytesOf(candidate).isPresent() ? Optional.of(candidate) : Optional.empty();
    }

    private static Optional<byte[]> bytesOf(String literal) {
        if (IPV4.matcher(literal).matches()) {
            // Parsed by hand: InetAddress would look up "999.1.1.1" as a host name.
            String[] octets = literal.split("\\.");
            byte[] bytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                int octet = Integer.parseInt(octets[i]);
                if (octet > 255) {
                    return Optional.empty();
                }
                bytes[i] = (byte) octet;
            }
            return Optional.of(bytes);
        }
        if (!literal.contains(":")) {
            return Optional.empty();
        }
        try {
            // A value with a colon is parsed as an IPv6 literal and never looked up.
            return Optional.of(InetAddress.getByName(literal).getAddress());
        } catch (UnknownHostException notAnAddress) {
            return Optional.empty();
        }
    }

    private record Range(byte[] network, int prefixBits) {

        static Range parse(String entry) {
            String value = entry.strip();
            int slash = value.indexOf('/');
            String address = slash < 0 ? value : value.substring(0, slash);
            byte[] bytes = literal(address)
                    .filter(found -> found.equals(address))
                    .flatMap(ClientAddressResolver::bytesOf)
                    .orElseThrow(() -> new IllegalArgumentException("not an IP address or CIDR range: " + entry));
            int bits = bytes.length * 8;
            int prefix;
            try {
                prefix = slash < 0 ? bits : Integer.parseInt(value.substring(slash + 1));
            } catch (NumberFormatException notANumber) {
                throw new IllegalArgumentException("not an IP address or CIDR range: " + entry, notANumber);
            }
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("prefix out of range: " + entry);
            }
            return new Range(bytes, prefix);
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int full = prefixBits / 8;
            for (int i = 0; i < full; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefixBits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (address[full] & mask) == (network[full] & mask);
        }
    }
}
