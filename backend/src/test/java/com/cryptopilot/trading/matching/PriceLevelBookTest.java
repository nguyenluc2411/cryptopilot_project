package com.cryptopilot.trading.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cryptopilot.trading.matching.PriceLevelBook.Side;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PriceLevelBookTest {

    private static final UUID A = UUID.fromString("019b76da-a800-7000-8000-0000000000a1");
    private static final UUID B = UUID.fromString("019b76da-a800-7000-8000-0000000000a2");
    private static final UUID C = UUID.fromString("019b76da-a800-7000-8000-0000000000a3");

    @Test
    void NSF07_anEmptyBook_firesNothing() {
        PriceLevelBook book = new PriceLevelBook(Side.BELOW);

        assertThat(book.fire(price("1"), price("1000"))).isEmpty();
        assertThat(book.isEmpty()).isTrue();
    }

    @Test
    void NSF07_aBelowBook_firesLevelsAtOrAboveTheLow_inclusive() {
        PriceLevelBook book = new PriceLevelBook(Side.BELOW);
        book.add(price("100"), A);
        book.add(price("99.99"), B);
        book.add(price("101"), C);

        assertThat(book.fire(price("100.00"), price("105"))).containsExactlyInAnyOrder(A, C);
        assertThat(book.fire(price("100"), price("105")))
                .as("fired levels are removed")
                .isEmpty();
        assertThat(book.fire(price("99.99"), price("99.99"))).containsExactly(B);
        assertThat(book.isEmpty()).isTrue();
    }

    @Test
    void NSF07_anAboveBook_firesLevelsAtOrBelowTheHigh_inclusive() {
        PriceLevelBook book = new PriceLevelBook(Side.ABOVE);
        book.add(price("100"), A);
        book.add(price("100.01"), B);
        book.add(price("90"), C);

        assertThat(book.fire(price("50"), price("100.000"))).containsExactlyInAnyOrder(A, C);
        assertThat(book.fire(price("100.01"), price("100.01"))).containsExactly(B);
    }

    @Test
    void NSF07_aRangeThatDoesNotReachALevel_firesNothing() {
        PriceLevelBook below = new PriceLevelBook(Side.BELOW);
        below.add(price("100"), A);
        PriceLevelBook above = new PriceLevelBook(Side.ABOVE);
        above.add(price("100"), B);

        assertThat(below.fire(price("100.01"), price("110"))).isEmpty();
        assertThat(above.fire(price("90"), price("99.99"))).isEmpty();
        assertThat(below.isEmpty()).isFalse();
        assertThat(above.isEmpty()).isFalse();
    }

    @Test
    void NSF07_idsAtTheSameLevel_fireTogether_andOneCanBeRemoved() {
        PriceLevelBook book = new PriceLevelBook(Side.BELOW);
        book.add(price("100"), A);
        book.add(price("100.0"), B);
        book.add(price("100"), C);

        book.remove(price("100.00"), B);
        book.remove(price("100"), UUID.randomUUID());
        book.remove(price("42"), A);

        assertThat(book.fire(price("100"), price("100"))).containsExactlyInAnyOrder(A, C);
    }

    @Test
    void NSF07_removingTheLastIdOfALevel_emptiesTheBook() {
        PriceLevelBook book = new PriceLevelBook(Side.ABOVE);
        book.add(price("100"), A);

        book.remove(price("100"), A);

        assertThat(book.isEmpty()).isTrue();
    }

    @Test
    void aBook_needsASide() {
        assertThatNullPointerException().isThrownBy(() -> new PriceLevelBook(null));
    }

    private static BigDecimal price(String value) {
        return new BigDecimal(value);
    }
}
