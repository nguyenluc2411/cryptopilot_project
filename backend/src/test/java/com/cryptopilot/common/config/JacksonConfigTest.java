package com.cryptopilot.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The decimal format of every response body: a string, in plain notation, with every digit it had
 * (TECHNICAL_DESIGN 5.4). Built from the customizer alone, so what is asserted is exactly what the
 * application's mapper is given.
 *
 * <p>Rule: ADR-008; TECHNICAL_DESIGN sections 5.4 and 8.
 */
class JacksonConfigTest {

    private final JsonMapper mapper = mapperWith(new JacksonConfig().decimalsAsStrings());

    /** A decimal is a string, so a browser never reads it into a double. */
    @Test
    void aDecimal_isWrittenAsAString() {
        assertThat(mapper.writeValueAsString(new Amount(new BigDecimal("1500.25"))))
                .isEqualTo("{\"value\":\"1500.25\"}");
    }

    /**
     * More significant digits than a double holds survive intact — the case the string exists for. A
     * number column of scale 12 and precision 28 is exactly this long.
     */
    @Test
    void aDecimalLongerThanADouble_keepsEveryDigit() {
        String price = "1234567890123456.123456789012";

        assertThat(mapper.writeValueAsString(new Amount(new BigDecimal(price))))
                .isEqualTo("{\"value\":\"" + price + "\"}");
    }

    /** A small value is written out in full, never as {@code 1E-8}. */
    @Test
    void aSmallDecimal_isWrittenInPlainNotation() {
        assertThat(mapper.writeValueAsString(new Amount(new BigDecimal("0.00000001"))))
                .isEqualTo("{\"value\":\"0.00000001\"}");
    }

    /** Reading is unchanged: a request may send a number or a string. */
    @Test
    void aDecimal_isReadFromANumberOrAString() {
        assertThat(mapper.readValue("{\"value\":1.5}", Amount.class).value()).isEqualByComparingTo("1.5");
        assertThat(mapper.readValue("{\"value\":\"1.5\"}", Amount.class).value())
                .isEqualByComparingTo("1.5");
    }

    private static JsonMapper mapperWith(JsonMapperBuilderCustomizer customizer) {
        JsonMapper.Builder builder = JsonMapper.builder();
        customizer.customize(builder);
        return builder.build();
    }

    record Amount(BigDecimal value) {}
}
