package com.cryptopilot.drawing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.drawing.model.ChartPoint;
import com.cryptopilot.drawing.model.enums.DrawingType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The anchors and text each drawing type needs.
 *
 * <p>Rule: D-149 (2); T-119.
 */
class DrawingRulesTest {

    private static final Instant T0 = Instant.parse("2026-11-10T00:00:00Z");

    @ParameterizedTest
    @EnumSource(
            value = DrawingType.class,
            names = {"LONG_POSITION", "SHORT_POSITION", "TEXT"},
            mode = EnumSource.Mode.EXCLUDE)
    void D149_everyOtherType_takesExactlyItsNumberOfAnchors(DrawingType type) {
        assertThatCode(() -> DrawingRules.check(type, points(type.anchors()), null))
                .doesNotThrowAnyException();
        assertField(() -> DrawingRules.check(type, points(type.anchors() + 1), null), "points");
        if (type.anchors() > 1) {
            assertField(() -> DrawingRules.check(type, points(type.anchors() - 1), null), "points");
        }
    }

    @Test
    void D149_theNumberOfAnchors_isThatOfEachTool() {
        assertThat(Arrays.stream(DrawingType.values()).filter(type -> type.anchors() == 1))
                .containsExactlyInAnyOrder(
                        DrawingType.HORIZONTAL_LINE,
                        DrawingType.HORIZONTAL_RAY,
                        DrawingType.VERTICAL_LINE,
                        DrawingType.TEXT);
        assertThat(Arrays.stream(DrawingType.values()).filter(type -> type.anchors() == 3))
                .containsExactlyInAnyOrder(
                        DrawingType.LONG_POSITION,
                        DrawingType.SHORT_POSITION,
                        DrawingType.PARALLEL_CHANNEL,
                        DrawingType.FIB_EXTENSION);
        assertThat(Arrays.stream(DrawingType.values()).filter(type -> type.anchors() == 4))
                .containsExactlyInAnyOrder(
                        DrawingType.ABCD_PATTERN,
                        DrawingType.TRIANGLE_PATTERN,
                        DrawingType.ELLIOTT_CORRECTION_WAVE,
                        DrawingType.ELLIOTT_DOUBLE_COMBO);
        assertThat(Arrays.stream(DrawingType.values()).filter(type -> type.anchors() == 5))
                .containsExactlyInAnyOrder(DrawingType.XABCD_PATTERN, DrawingType.CYPHER_PATTERN);
        assertThat(Arrays.stream(DrawingType.values()).filter(type -> type.anchors() == 6))
                .containsExactlyInAnyOrder(
                        DrawingType.THREE_DRIVES_PATTERN,
                        DrawingType.ELLIOTT_IMPULSE_WAVE,
                        DrawingType.ELLIOTT_TRIANGLE_WAVE,
                        DrawingType.ELLIOTT_TRIPLE_COMBO);
        assertThat(Arrays.stream(DrawingType.values()).filter(type -> type.anchors() == 7))
                .containsExactly(DrawingType.HEAD_AND_SHOULDERS);
        assertThat(Arrays.stream(DrawingType.values())
                        .mapToInt(DrawingType::anchors)
                        .max())
                .hasValue(DrawingType.MAX_ANCHORS);
    }

    /** Anchors are entry, stop, target. */
    @ParameterizedTest
    @CsvSource({
        "LONG_POSITION,  100, 90,  110, true",
        "LONG_POSITION,  100, 100, 110, false",
        "LONG_POSITION,  100, 90,  100, false",
        "LONG_POSITION,  100, 110, 90,  false",
        "SHORT_POSITION, 100, 110, 90,  true",
        "SHORT_POSITION, 100, 100, 90,  false",
        "SHORT_POSITION, 100, 110, 100, false",
        "SHORT_POSITION, 100, 90,  110, false"
    })
    void D149_aPosition_needsItsStopAndTargetOnTheirSidesOfTheEntry(
            DrawingType type, String entry, String stop, String target, boolean accepted) {
        List<ChartPoint> points = List.of(point(entry), point(stop), point(target));
        if (accepted) {
            assertThatCode(() -> DrawingRules.check(type, points, null)).doesNotThrowAnyException();
        } else {
            assertField(() -> DrawingRules.check(type, points, null), "points");
        }
    }

    @Test
    void D149_aTextDrawing_needsText_theOthersMayCarryIt() {
        assertThatCode(() -> DrawingRules.check(DrawingType.TEXT, points(1), "note"))
                .doesNotThrowAnyException();
        assertField(() -> DrawingRules.check(DrawingType.TEXT, points(1), null), "text");
        assertField(() -> DrawingRules.check(DrawingType.TEXT, points(1), " \t"), "text");
        assertField(() -> DrawingRules.check(DrawingType.TEXT, points(2), "note"), "points");
        assertThatCode(() -> DrawingRules.check(DrawingType.HORIZONTAL_LINE, points(1), "support"))
                .doesNotThrowAnyException();
    }

    private static void assertField(ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.errors())
                .containsEntry(field, "MSG01"));
    }

    private static List<ChartPoint> points(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new ChartPoint(T0.plusSeconds(3600L * i), BigDecimal.valueOf(100 + i)))
                .toList();
    }

    private static ChartPoint point(String price) {
        return new ChartPoint(T0, new BigDecimal(price));
    }
}
