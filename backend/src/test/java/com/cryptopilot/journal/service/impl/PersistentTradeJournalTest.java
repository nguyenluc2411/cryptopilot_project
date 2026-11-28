package com.cryptopilot.journal.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.journal.entity.TradingJournal;
import com.cryptopilot.journal.model.PlanPosition;
import com.cryptopilot.journal.repository.LevelChangeLog;
import com.cryptopilot.journal.repository.TradingJournalRepository;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.SimulatedFillLearning;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The journal's answers to the plans, with the repository replaced. */
class PersistentTradeJournalTest {

    private static final UUID PLAN = UUID.fromString("019b76da-a800-7000-8000-0000000000e1");
    private static final UUID OTHER_PLAN = UUID.fromString("019b76da-a800-7000-8000-0000000000e2");
    private static final UUID USER = UUID.fromString("019b76da-a800-7000-8000-0000000000e3");

    private static final Instant NOW = Instant.parse("2026-10-25T09:00:00Z");

    private final TradingJournalRepository records = mock(TradingJournalRepository.class);
    private final LevelChangeLog levelChanges = mock(LevelChangeLog.class);
    private final PersistentTradeJournal journal =
            new PersistentTradeJournal(records, levelChanges, Clock.fixed(NOW, ZoneOffset.UTC));

    /** BR-70: the learning data of the plan's fill goes into its record, which is answered by id. */
    @Test
    void BR70_theFillLearning_isAddedToThePlansRecord_orFindsNone() {
        TradingJournal record = record();
        when(records.findByPlanId(PLAN)).thenReturn(Optional.of(record));
        when(records.findByPlanId(OTHER_PLAN)).thenReturn(Optional.empty());
        SimulatedFillLearning learning = new SimulatedFillLearning(
                new BigDecimal("100"), new BigDecimal("0.2"), BigDecimal.TEN, "1h", "SWING v1", null, null, null, null);

        assertThat(journal.recordFillLearning(PLAN, learning)).contains(record.getId());
        assertThat(record.getPlannedEntryPrice()).isEqualByComparingTo("100");
        assertThat(record.getAnalysisTimeframe()).isEqualTo("1h");
        assertThat(journal.recordFillLearning(OTHER_PLAN, learning)).isEmpty();
    }

    @Test
    void UC20_thePositionOfAPlan_isItsRecord_orNothing() {
        TradingJournal record = record();
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

    /** The plan's record as its paper entry fill opens it (RF-35): Spot LONG 2 at 100, maker, SL 95, TP 110. */
    private static TradingJournal record() {
        return TradingJournal.openFromPaper(
                USER,
                UUID.randomUUID(),
                PLAN,
                MarketType.SPOT,
                Direction.LONG,
                null,
                new BigDecimal("100"),
                new BigDecimal("2"),
                Instant.parse("2026-10-25T08:00:00Z"),
                LiquidityRole.MAKER,
                new BigDecimal("0.2"),
                new BigDecimal("95"),
                new BigDecimal("110"),
                null,
                null);
    }
}
