package com.cryptopilot.billing.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** TECHNICAL_DESIGN 7.11: CP + UTC yyMMddHHmmss + four base-36 characters. */
class OrderCodeGeneratorTest {

    private static final Instant NOW = Instant.parse("2026-11-07T15:30:45.987Z");

    @Test
    void TD711_theCode_isThePrefixTheUtcSecondAndFourBase36Characters() {
        String code = OrderCodeGenerator.next(NOW, new SplittableRandom(7));

        assertThat(code).hasSize(18).startsWith("CP261107153045").matches("CP\\d{12}[0-9A-Z]{4}");
    }

    /** The random part draws from the whole alphabet, from the first character to the last. */
    @Test
    void TD711_theSuffix_usesTheWholeBase36Alphabet() {
        Random fixed = new Random() {
            private int next;

            @Override
            public int nextInt(int bound) {
                return next++ % 2 == 0 ? 0 : bound - 1;
            }
        };

        assertThat(OrderCodeGenerator.next(NOW, fixed)).endsWith("0Z0Z");
    }

    @Test
    void TD711_codesOfTheSameSecond_differ() {
        SplittableRandom random = new SplittableRandom(42);
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            codes.add(OrderCodeGenerator.next(NOW, random));
        }

        assertThat(codes).hasSizeGreaterThan(990);
    }
}
