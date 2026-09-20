package com.cryptopilot.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PageResponseTest {

    @Test
    void page_keepsTheItemsAndTheCountsItWasGiven() {
        PageResponse<String> page = new PageResponse<>(List.of("BTCUSDT", "ETHUSDT"), 2, 20, 41);

        assertThat(page.items()).containsExactly("BTCUSDT", "ETHUSDT");
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.pageSize()).isEqualTo(20);
        assertThat(page.total()).isEqualTo(41);
    }

    @Test
    void emptyLastPage_isAValidAnswer() {
        assertThat(new PageResponse<>(List.of(), 3, 20, 40).items()).isEmpty();
    }

    @Test
    void items_areCopied_soTheResponseCannotChangeAfterItIsBuilt() {
        List<String> source = new ArrayList<>(List.of("BTCUSDT"));

        PageResponse<String> page = new PageResponse<>(source, 1, 20, 1);
        source.add("ETHUSDT");

        assertThat(page.items()).containsExactly("BTCUSDT");
    }

    @Test
    void nullItems_areRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PageResponse<String>(null, 1, 20, 0))
                .withMessageContaining("items must not be null");
    }

    @Test
    void nullInsideTheItems_isRejected() {
        assertThatNullPointerException().isThrownBy(() -> new PageResponse<>(Arrays.asList("BTCUSDT", null), 1, 20, 2));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void pageBelowOne_isRejected(int page) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new PageResponse<>(List.of(), page, 20, 0))
                .withMessageContaining("page must be at least 1");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, PageResponse.MAX_PAGE_SIZE + 1})
    void pageSizeOutsideTheAllowedRange_isRejected(int pageSize) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new PageResponse<>(List.of(), 1, pageSize, 0))
                .withMessageContaining("pageSize must be between 1 and " + PageResponse.MAX_PAGE_SIZE);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, PageResponse.MAX_PAGE_SIZE})
    void pageSizeOnTheBoundary_isAccepted(int pageSize) {
        assertThat(new PageResponse<>(List.of(), 1, pageSize, 0).pageSize()).isEqualTo(pageSize);
    }

    @Test
    void negativeTotal_isRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new PageResponse<>(List.of(), 1, 20, -1))
                .withMessageContaining("total must not be negative");
    }
}
