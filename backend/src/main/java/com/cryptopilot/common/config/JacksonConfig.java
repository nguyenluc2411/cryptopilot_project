package com.cryptopilot.common.config;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamWriteFeature;

/**
 * How every response body writes a decimal: as a string, in plain notation.
 *
 * <p>A JSON number is read by a browser into an IEEE 754 double, which has about sixteen significant
 * digits. A price column holds twenty-eight, a USDT amount twenty-eight with eight decimals, so a
 * value written as a number can reach the client already changed — silently, and differently
 * depending on the magnitude. Written as a string it reaches the client exactly as it was stored, and
 * the client formats it with a decimal library (TECHNICAL_DESIGN 5.4 and 8).
 *
 * <p>Plain notation, because {@link BigDecimal#toString()} switches to an exponent for small values
 * and {@code 1E-8} is a legal decimal that no screen should ever show.
 *
 * <p>Set once here rather than per field, so a record added next sprint cannot forget it. Reading is
 * unchanged: a request may send a decimal as a number or a string, and either becomes a
 * {@link BigDecimal} without passing through a double.
 *
 * <p>Rule: ADR-008; TECHNICAL_DESIGN sections 5.4 and 8.
 *
 * <p>Reference: IEEE (2019). <i>IEEE Standard for Floating-Point Arithmetic</i>, IEEE 754-2019
 * (binary64 carries 53 significant bits, about 15.95 decimal digits).
 * <p>Reference: Bray, T. (2017). RFC 8259, <i>The JavaScript Object Notation (JSON) Data Interchange
 * Format</i>, section 6 (interoperability of numbers beyond IEEE 754 double precision is not assured).
 */
@Configuration
public class JacksonConfig {

    /** Decimals as plain-notation strings in every body the application writes. */
    @Bean
    JsonMapperBuilderCustomizer decimalsAsStrings() {
        return builder -> builder.enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .withConfigOverride(
                        BigDecimal.class,
                        override -> override.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)));
    }
}
