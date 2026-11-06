package com.cryptopilot.journal.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.journal.dto.response.JournalEntryDetailResponse;
import com.cryptopilot.journal.dto.response.JournalEntryResponse;
import com.cryptopilot.journal.entity.TradingJournal;
import com.cryptopilot.journal.model.JournalListQuery;
import com.cryptopilot.journal.repository.TradingJournalRepository;
import com.cryptopilot.trading.StrategyCatalog;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * The parts of the journal the API cannot see: the strategy names of a page come from one catalogue call, a page is
 * refused before anything is read, and the default end of a period is a day of UTC+7.
 *
 * <p>Rule: UC-22; SRS 3.6.1; CR-08; D-139.
 */
class JournalServiceImplTest {

    /** Still the 29th in UTC, already the 30th in UTC+7. */
    private static final Instant LATE = Instant.parse("2026-09-29T17:30:00Z");

    private static final JournalListQuery NO_FILTERS =
            new JournalListQuery(null, null, null, null, null, null, null, null, null, null);

    private final UUID trader = UUID.randomUUID();
    private final TradingJournalRepository journal = mock(TradingJournalRepository.class);
    private final StrategyCatalog strategies = mock(StrategyCatalog.class);
    private final JournalServiceImpl service =
            new JournalServiceImpl(journal, strategies, Clock.fixed(LATE, ZoneOffset.UTC));

    @Test
    void UC22_aPage_readsItsStrategyNamesInOneCatalogueCall() {
        UUID breakout = UUID.randomUUID();
        UUID swing = UUID.randomUUID();
        searchAnswers(record(breakout), record(swing), record(breakout), record(null));
        when(strategies.names(anyCollection())).thenReturn(Map.of(breakout, "Breakout", swing, "Swing"));

        PageResponse<JournalEntryResponse> page = service.list(trader, NO_FILTERS);

        verify(strategies, times(1)).names(Set.of(breakout, swing));
        assertThat(page.items())
                .extracting(JournalEntryResponse::strategyName)
                .containsExactly("Breakout", "Swing", "Breakout", null);
    }

    @Test
    void CR08_defaultEnd_isTomorrowInUtcPlus7NotInUtc() {
        searchAnswers();
        when(strategies.names(anyCollection())).thenReturn(Map.of());

        service.list(trader, NO_FILTERS);

        ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        verify(journal)
                .search(
                        eq(trader),
                        anyCollection(),
                        anyCollection(),
                        anyCollection(),
                        any(),
                        end.capture(),
                        anyBoolean(),
                        any(),
                        anyBoolean(),
                        any(),
                        anyBoolean(),
                        anyInt(),
                        any());
        assertThat(end.getValue()).isEqualTo(Instant.parse("2026-09-30T17:00:00Z"));
    }

    @Test
    void D139_aBadPeriod_isRefusedBeforeAnythingIsRead() {
        JournalListQuery future = new JournalListQuery(
                null, null, null, LocalDate.parse("2030-01-01"), null, null, null, null, null, null);

        assertThatThrownBy(() -> service.list(trader, future)).isInstanceOf(FieldValidationException.class);

        verifyNoInteractions(journal, strategies);
    }

    @Test
    void UC22_oneTrade_keepsItsStrategyNameAndReview() {
        UUID breakout = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        TradingJournal record = record(breakout);
        when(record.getMistakes()).thenReturn("Moved the stop");
        when(journal.findByIdAndUserId(id, trader)).thenReturn(Optional.of(record));
        when(strategies.names(anyCollection())).thenReturn(Map.of(breakout, "Breakout"));

        JournalEntryDetailResponse detail = service.detail(trader, id);

        assertThat(detail.entry().strategyName()).isEqualTo("Breakout");
        assertThat(detail.mistakes()).isEqualTo("Moved the stop");
    }

    private void searchAnswers(TradingJournal... records) {
        Page<TradingJournal> page = new PageImpl<>(List.of(records), PageRequest.of(0, 20), records.length);
        when(journal.search(
                        any(),
                        anyCollection(),
                        anyCollection(),
                        anyCollection(),
                        any(),
                        any(),
                        anyBoolean(),
                        any(),
                        anyBoolean(),
                        any(),
                        anyBoolean(),
                        anyInt(),
                        any()))
                .thenReturn(page);
    }

    private static TradingJournal record(UUID strategy) {
        TradingJournal record = mock(TradingJournal.class);
        when(record.getStrategyId()).thenReturn(strategy);
        when(record.getStatus()).thenReturn(PositionStatus.OPEN);
        return record;
    }
}
