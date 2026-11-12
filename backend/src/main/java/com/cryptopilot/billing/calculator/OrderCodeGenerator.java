package com.cryptopilot.billing.calculator;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * The code a Trader and a gateway know an order by: {@code CP}, the creation instant in UTC as {@code yyMMddHHmmss}
 * and four random base-36 characters, e.g. {@code CP261107153045K7QZ}. It is 18 letters and digits, which every
 * gateway accepts as its order reference, and it sorts by creation time. Two orders created in the same second share
 * a code once in 36^4 (about 1.7 million); {@code uq_subscription_order_code} refuses the second and the service draws
 * again.
 *
 * <p>Rule: TECHNICAL_DESIGN 7.11.
 */
public final class OrderCodeGenerator {

    /** Every code starts with it. */
    public static final String PREFIX = "CP";

    static final int SUFFIX_LENGTH = 4;

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyMMddHHmmss").withZone(ZoneOffset.UTC);

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private OrderCodeGenerator() {}

    /** A new code for an order created at {@code now}. */
    public static String next(Instant now, RandomGenerator random) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(random, "random must not be null");
        StringBuilder code = new StringBuilder(PREFIX).append(TIMESTAMP.format(now));
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
