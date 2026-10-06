package com.cryptopilot.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.common.exception.FieldValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** The one check every list endpoint applies to its page parameters (CR-04). */
class PagingTest {

    @Test
    void CR04_noParameters_meanTheFirstPageOfTwenty() {
        PageRequest request = Paging.of(null, null);

        assertThat(request.getPageNumber()).isZero();
        assertThat(request.getPageSize()).isEqualTo(Paging.DEFAULT_PAGE_SIZE).isEqualTo(20);
        assertThat(request.getSort().isUnsorted()).isTrue();
    }

    @Test
    void CR04_theBounds_areAccepted_andTheSortKept() {
        Sort newest = Sort.by(Sort.Order.desc("createdAt"));

        PageRequest request = Paging.of(3, PageResponse.MAX_PAGE_SIZE, newest);

        assertThat(request.getPageNumber()).isEqualTo(2);
        assertThat(request.getPageSize()).isEqualTo(100);
        assertThat(request.getSort()).isEqualTo(newest);
        assertThat(Paging.of(1, 1).getPageSize()).isOne();
    }

    @Test
    void CR04_aPageBelowOne_isRefusedOnItsParameter() {
        assertThatExceptionOfType(FieldValidationException.class)
                .isThrownBy(() -> Paging.of(0, null))
                .satisfies(refused -> assertThat(refused.errors()).containsEntry("page", "MSG15"));
    }

    @Test
    void CR04_aPageSizeOutsideOneToAHundred_isRefusedOnItsParameter() {
        assertThatExceptionOfType(FieldValidationException.class)
                .isThrownBy(() -> Paging.of(null, 101))
                .satisfies(refused -> assertThat(refused.errors()).containsEntry("pageSize", "MSG15"));
        assertThatExceptionOfType(FieldValidationException.class).isThrownBy(() -> Paging.of(null, 0));
    }
}
