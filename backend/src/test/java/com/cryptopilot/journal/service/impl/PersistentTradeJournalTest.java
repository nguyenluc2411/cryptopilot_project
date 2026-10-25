package com.cryptopilot.journal.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.journal.entity.TradingJournal;
import com.cryptopilot.journal.model.PlanPosition;
import com.cryptopilot.journal.repository.TradingJournalRepository;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.SimulatedTradeOpening;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The journal's answers to the plans, with the repository replaced. */
class PersistentTradeJournalTest {

    private static final UUID PLAN = UUID.fromString("019b76da-a800-7000-8000-0000000000e1");
    private static final UUID OTHER_PLAN = UUID.fromString("019b76da-a800-7000-8000-0000000000e2");
    private static final UUID USER = UUID.fromString("019b76da-a800-7000-8000-0000000000e3");

    private final TradingJournalRepository records = mock(TradingJournalRepository.class);
    private final PersistentTradeJournal journal = new PersistentTradeJournal(records);

    @Test
    void BR34_anOpening_isWrittenAtOnceAsAnOpenRecord() {
        when(records.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        journal.openSimulatedTrade(opening());

        ArgumentCaptor<TradingJournal> written = ArgumentCaptor.forClass(TradingJournal.class);
        verify(records).saveAndFlush(written.capture());
        assertThat(written.getValue().getPlanId()).isEqualTo(PLAN);
        assertThat(written.getValue().getStatus()).isEqualTo(PositionStatus.OPEN);
    }

    @Test
    void BR62_theOpenPositions_areThePlansOfTheOpenRecords() {
        when(records.findOpenPlanIds(USER)).thenReturn(List.of(PLAN, OTHER_PLAN));

        assertThat(journal.openPositionPlans(USER)).isEqualTo(Set.of(PLAN, OTHER_PLAN));
    }

    @Test
    void UC20_thePositionOfAPlan_isItsRecord_orNothing() {
        TradingJournal record = TradingJournal.openSimulated(opening());
        when(records.findByPlanId(PLAN)).thenReturn(Optional.of(record));
        when(records.findByPlanId(OTHER_PLAN)).thenReturn(Optional.empty());

        assertThat(journal.positionOf(PLAN)).contains(record.toPosition());
        assertThat(journal.positionOf(OTHER_PLAN)).isEmpty();
    }

    @Test
    void SCR16_theStatusesOfSomePlans_areReadInOneQuery() {
        when(records.findStatuses(List.of(PLAN, OTHER_PLAN)))
                .thenReturn(List.of(new PlanPosition(PLAN, PositionStatus.CLOSED)));

        assertThat(journal.positionStatuses(List.of(PLAN, OTHER_PLAN))).isEqualTo(Map.of(PLAN, PositionStatus.CLOSED));
    }

    @Test
    void SCR16_noPlans_areAnsweredWithoutAQuery() {
        assertThat(journal.positionStatuses(List.of())).isEmpty();

        verifyNoInteractions(records);
    }

    private static SimulatedTradeOpening opening() {
        return new SimulatedTradeOpening(
                PLAN,
                USER,
                UUID.randomUUID(),
                MarketType.SPOT,
                Direction.LONG,
                null,
                new BigDecimal("100"),
                Instant.parse("2026-10-25T08:00:00Z"),
                new BigDecimal("2"),
                LiquidityRole.MAKER,
                new BigDecimal("0.2"),
                new BigDecimal("95"),
                new BigDecimal("110"));
    }
}
