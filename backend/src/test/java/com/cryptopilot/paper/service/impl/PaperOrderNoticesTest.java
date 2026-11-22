package com.cryptopilot.paper.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.paper.event.PaperOrderNotice;
import com.cryptopilot.paper.model.enums.OrderStatus;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Which ended paper orders are told to their Trader (TR-29): every status against the reasons each path gives.
 *
 * <p>Rule: TR-29; NSF-15.
 */
class PaperOrderNoticesTest {

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(
            nullValues = "null",
            value = {
                "FILLED, null, FILLED",
                "FILLED, DEPTH_EXHAUSTED, FILLED",
                "FILLED, TRIGGERED, TRIGGERED",
                "CANCELED, null, null",
                "CANCELED, OCO_OTHER_EXECUTED, null",
                "CANCELED, PAIR_NOT_TRADING, CANCELED",
                "CANCELED, LIQUIDATION, CANCELED",
                "CANCELED, MARGIN_INSUFFICIENT, CANCELED",
                "CANCELED, POSITION_CLOSED, CANCELED",
                "CANCELED, REDUCE_ONLY_NOTHING_TO_REDUCE, CANCELED",
                "CANCELED, NOTHING_TO_CLOSE, CANCELED",
                "CANCELED, MAX_POSITION_AT_LEVERAGE, CANCELED",
                "CANCELED, MARK_STALE, CANCELED",
                "CANCELED, PERCENT_PRICE, CANCELED",
                "CANCELED, INSUFFICIENT_BALANCE, CANCELED",
                "EXPIRED, GOOD_TILL_DATE, EXPIRED",
                "EXPIRED, NOT_FILLABLE_ON_ARRIVAL, null",
                "EXPIRED, POST_ONLY_WOULD_TAKE, null",
                "EXPIRED, WOULD_TRIGGER, null",
                "REJECTED, INSUFFICIENT_BALANCE, null",
                "NEW, null, null",
                "PENDING_NEW, null, null",
                "PARTIALLY_FILLED, null, null"
            })
    void TR29_anOrdersEnd_isANotice_onlyWhenTheTraderIsToBeTold(
            OrderStatus status, String reason, PaperOrderNotice.Kind expected) {
        assertThat(PaperOrderNotices.kindOf(status, reason)).isEqualTo(Optional.ofNullable(expected));
    }
}
