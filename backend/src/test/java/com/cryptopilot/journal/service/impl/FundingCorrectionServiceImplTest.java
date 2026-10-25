package com.cryptopilot.journal.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.journal.entity.TradingJournal;
import com.cryptopilot.journal.repository.TradingJournalRepository;
import com.cryptopilot.trading.ClosedTradeFunding;
import com.cryptopilot.trading.TradeFunding;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

/**
 * The correction of a closed trade's incomplete funding: a recomputed answer that differs is stored, the same answer is
 * not written again.
 *
 * <p>Rule: BR-37, BR-38; A-05; D-97 note of 2026-10-24.
 */
class FundingCorrectionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-26T09:00:00Z");
    private static final Instant ENTRY = Instant.parse("2026-10-25T00:00:00Z");
    private static final Instant EXIT = Instant.parse("2026-10-25T20:00:00Z");
    private static final Instant MISSING = Instant.parse("2026-10-25T16:00:00Z");

    private final TradingJournalRepository records = mock(TradingJournalRepository.class);
    private final TradeFunding funding = mock(TradeFunding.class);
    private final FundingCorrectionServiceImpl corrections =
            new FundingCorrectionServiceImpl(records, funding, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void BR37_theLateSettlement_clearsTheFlag_andFixesTheAmount() {
        TradingJournal record = incomplete("-0.0101", MISSING);
        when(records.findIncompleteFunding(Limit.of(50))).thenReturn(List.of(record));
        when(funding.fundingOf(record.getPairId(), Direction.LONG, new BigDecimal("2"), ENTRY, EXIT))
                .thenReturn(new ClosedTradeFunding(new BigDecimal("-0.0202"), false, List.of()));
        when(records.correctFunding(any(), any(), anyBoolean(), any(), any())).thenReturn(1);

        assertThat(corrections.correctIncompleteFunding(50)).isEqualTo(1);

        verify(records).correctFunding(eq(record.getId()), eq(new BigDecimal("-0.0202")), eq(false), isNull(), eq(NOW));
    }

    @Test
    void BR37_aStillIncompleteFundingWithAnotherSum_isStoredWithItsMissingTimes() {
        TradingJournal record = incomplete("-0.0101", MISSING);
        when(records.findIncompleteFunding(Limit.of(50))).thenReturn(List.of(record));
        Instant other = Instant.parse("2026-10-25T08:00:00Z");
        when(funding.fundingOf(any(), any(), any(), any(), any()))
                .thenReturn(new ClosedTradeFunding(new BigDecimal("-0.0303"), true, List.of(other)));
        when(records.correctFunding(any(), any(), anyBoolean(), any(), any())).thenReturn(1);

        assertThat(corrections.correctIncompleteFunding(50)).isEqualTo(1);

        verify(records)
                .correctFunding(record.getId(), new BigDecimal("-0.0303"), true, "{\"2026-10-25T08:00:00Z\"}", NOW);
    }

    @Test
    void BR37_theSameAnswer_isNotWrittenAgain() {
        TradingJournal record = incomplete("-0.0101", MISSING);
        when(records.findIncompleteFunding(Limit.of(50))).thenReturn(List.of(record));
        when(funding.fundingOf(any(), any(), any(), any(), any()))
                .thenReturn(new ClosedTradeFunding(new BigDecimal("-0.01010000"), true, List.of(MISSING)));

        assertThat(corrections.correctIncompleteFunding(50)).isZero();

        verify(records, never()).correctFunding(any(), any(), anyBoolean(), any(), any());
    }

    /** The same sum with other missing times, or with none named, is a change. */
    @Test
    void BR37_otherMissingTimes_areAChange() {
        TradingJournal named = incomplete("-0.0101", MISSING);
        TradingJournal unnamed = incomplete("-0.0101");
        when(records.findIncompleteFunding(Limit.of(50))).thenReturn(List.of(named, unnamed));
        when(funding.fundingOf(any(), any(), any(), any(), any()))
                .thenReturn(
                        new ClosedTradeFunding(new BigDecimal("-0.0101"), true, List.of()),
                        new ClosedTradeFunding(new BigDecimal("-0.0101"), true, List.of(MISSING)));
        when(records.correctFunding(any(), any(), anyBoolean(), any(), any())).thenReturn(1, 0);

        assertThat(corrections.correctIncompleteFunding(50))
                .as("the second row was corrected by another run first")
                .isEqualTo(1);
    }

    /** A CLOSED Futures LONG of quantity 2 from {@link #ENTRY} to {@link #EXIT} with this known funding. */
    private static TradingJournal incomplete(String amount, Instant... missing) {
        TradingJournal record = mock(TradingJournal.class);
        when(record.getId()).thenReturn(UUID.randomUUID());
        when(record.getPairId()).thenReturn(UUID.randomUUID());
        when(record.getDirection()).thenReturn(Direction.LONG);
        when(record.getQuantity()).thenReturn(new BigDecimal("2"));
        when(record.getEntryTime()).thenReturn(ENTRY);
        when(record.getExitTime()).thenReturn(EXIT);
        when(record.getFundingFeeAmount()).thenReturn(new BigDecimal(amount));
        when(record.getMissingFundingTimes()).thenReturn(missing.length == 0 ? null : missing);
        return record;
    }
}
