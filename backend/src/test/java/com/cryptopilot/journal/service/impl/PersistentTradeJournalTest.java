package com.cryptopilot.journal.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.journal.entity.TradingJournal;
import com.cryptopilot.journal.model.PlanPosition;
import com.cryptopilot.journal.repository.LevelChangeLog;
import com.cryptopilot.journal.repository.TradingJournalRepository;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.SimulatedTradeClosing;
import com.cryptopilot.trading.SimulatedTradeOpening;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The journal's answers to the plans and the matching engine, with the repository replaced. */
class PersistentTradeJournalTest {

    private static final UUID PLAN = UUID.fromString("019b76da-a800-7000-8000-0000000000e1");
    private static final UUID OTHER_PLAN = UUID.fromString("019b76da-a800-7000-8000-0000000000e2");
    private static final UUID USER = UUID.fromString("019b76da-a800-7000-8000-0000000000e3");

    private static final Instant NOW = Instant.parse("2026-10-25T09:00:00Z");

    private final TradingJournalRepository records = mock(TradingJournalRepository.class);
    private final LevelChangeLog levelChanges = mock(LevelChangeLog.class);
    private final PersistentTradeJournal journal =
            new PersistentTradeJournal(records, levelChanges, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void BR34_anOpening_isWrittenAtOnceAsAnOpenRecord_andAnsweredAsTheEngineTracksIt() {
        when(records.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        OpenTrade opened = journal.openSimulatedTrade(opening());

        ArgumentCaptor<TradingJournal> written = ArgumentCaptor.forClass(TradingJournal.class);
        verify(records).saveAndFlush(written.capture());
        assertThat(written.getValue().getPlanId()).isEqualTo(PLAN);
        assertThat(written.getValue().getStatus()).isEqualTo(PositionStatus.OPEN);
        assertThat(opened).isEqualTo(written.getValue().toOpenTrade());
    }

    @Test
    void NSF07_theOpenTrades_areTheOpenSimulatedRecords() {
        TradingJournal record = TradingJournal.openSimulated(opening());
        when(records.findOpenSimulated()).thenReturn(List.of(record));
        when(records.findOpenSimulated(record.getId())).thenReturn(Optional.of(record));

        assertThat(journal.openSimulatedTrades()).containsExactly(record.toOpenTrade());
        assertThat(journal.openTrade(record.getId())).contains(record.toOpenTrade());
    }

    @Test
    void BR35_aClose_isOneCompareAndSet_withTheMissingFundingTimesAsAnArray() {
        UUID position = UUID.randomUUID();
        when(records.closeIfOpen(
                        eq(position),
                        eq(3L),
                        any(),
                        any(),
                        eq("STOP_LOSS"),
                        any(),
                        any(),
                        any(),
                        eq(true),
                        eq("{\"2026-10-25T08:00:00Z\",\"2026-10-25T16:00:00Z\"}"),
                        eq(NOW)))
                .thenReturn(1);

        assertThat(journal.closeSimulatedTrade(closing(
                        position,
                        new BigDecimal("-0.5"),
                        true,
                        List.of(Instant.parse("2026-10-25T08:00:00Z"), Instant.parse("2026-10-25T16:00:00Z")))))
                .isTrue();
    }

    @Test
    void BR35_aCloseThatChangedNothing_answersFalse_andAFundingWithNothingMissingWritesNoArray() {
        UUID position = UUID.randomUUID();
        when(records.closeIfOpen(
                        eq(position),
                        anyLong(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        anyBoolean(),
                        isNull(),
                        any()))
                .thenReturn(0);

        assertThat(journal.closeSimulatedTrade(closing(position, null, false, List.of())))
                .isFalse();
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
                new BigDecimal("110"),
                null);
    }

    private static SimulatedTradeClosing closing(
            UUID position, BigDecimal funding, boolean incomplete, List<Instant> missing) {
        return new SimulatedTradeClosing(
                position,
                3,
                new BigDecimal("95"),
                Instant.parse("2026-10-25T17:00:00Z"),
                CloseReason.STOP_LOSS,
                new BigDecimal("-10"),
                new BigDecimal("0.2"),
                funding,
                incomplete,
                missing);
    }
}
