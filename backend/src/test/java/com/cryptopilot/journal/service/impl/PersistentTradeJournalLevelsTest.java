package com.cryptopilot.journal.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.journal.entity.TradingJournal;
import com.cryptopilot.journal.repository.LevelChangeLog;
import com.cryptopilot.journal.repository.TradingJournalRepository;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.PositionLevelChange;
import com.cryptopilot.trading.PositionLevels;
import com.cryptopilot.trading.SimulatedLevelChange;
import com.cryptopilot.trading.SimulatedTradeOpening;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * TP-E, the journal's side of a change of an open position's levels: the compare-and-set, the history row written only
 * when it wins, the original levels kept at the open, and the reads the plan detail and the engine use.
 *
 * <p>Rule: BR-35, BR-42; D-119 (5), D-124.
 */
class PersistentTradeJournalLevelsTest {

    private static final UUID PLAN = UUID.fromString("019b76da-a800-7000-8000-0000000000e4");
    private static final UUID USER = UUID.fromString("019b76da-a800-7000-8000-0000000000e5");
    private static final Instant NOW = Instant.parse("2026-10-25T09:00:00Z");

    private final TradingJournalRepository records = mock(TradingJournalRepository.class);
    private final LevelChangeLog levelChanges = mock(LevelChangeLog.class);
    private final PersistentTradeJournal journal =
            new PersistentTradeJournal(records, levelChanges, Clock.fixed(NOW, ZoneOffset.UTC));

    /** The compare-and-set wins: the history row is written and the position answered at its new version. */
    @Test
    void D124_aChangeThatWins_writesItsHistory_andAnswersThePosition() {
        TradingJournal record = TradingJournal.openSimulated(opening());
        SimulatedLevelChange change = change(record.getId(), true);
        when(records.changeLevelsIfOpen(record.getId(), 3L, new BigDecimal("93"), new BigDecimal("110"), true, NOW))
                .thenReturn(1);
        when(records.findOpenSimulated(record.getId())).thenReturn(Optional.of(record));

        Optional<OpenTrade> changed = journal.changeSimulatedLevels(change);

        assertThat(changed).map(OpenTrade::positionId).contains(record.getId());
        verify(levelChanges).append(change.history(), NOW);
    }

    /** The compare-and-set loses (an exit or another change first): no history, nothing answered. */
    @Test
    void D124_aChangeThatLoses_writesNoHistory() {
        when(records.changeLevelsIfOpen(any(), anyLong(), any(), any(), anyBoolean(), any()))
                .thenReturn(0);

        assertThat(journal.changeSimulatedLevels(change(UUID.randomUUID(), false)))
                .isEmpty();

        verifyNoInteractions(levelChanges);
    }

    /** The open keeps the plan's levels as the original ones, which no change moves. */
    @Test
    void D124_theOpen_keepsTheOriginalLevels_andTheLevelsReadBackWithTheirHistory() {
        TradingJournal record = TradingJournal.openSimulated(opening());
        List<PositionLevelChange> history =
                List.of(change(record.getId(), false).history());
        when(records.findByPlanId(PLAN)).thenReturn(Optional.of(record));
        when(levelChanges.of(record.getId())).thenReturn(history);

        PositionLevels levels = journal.levelsOf(PLAN).orElseThrow();

        assertThat(levels.originalStopLoss()).isEqualByComparingTo("95");
        assertThat(levels.originalTakeProfit()).isEqualByComparingTo("110");
        assertThat(levels.stopLoss()).isEqualByComparingTo("95");
        assertThat(levels.stopWidened()).isFalse();
        assertThat(levels.changeCount()).isZero();
        assertThat(levels.changes()).isEqualTo(history);
        assertThat(journal.levelsOf(UUID.randomUUID())).isEmpty();
    }

    @Test
    void D124_theUsersOpenTrades_andTheOpenHistory_areReadFromTheJournal() {
        TradingJournal record = TradingJournal.openSimulated(opening());
        when(records.findOpenSimulatedOf(USER)).thenReturn(List.of(record));
        Map<UUID, List<PositionLevelChange>> open =
                Map.of(record.getId(), List.of(change(record.getId(), false).history()));
        when(levelChanges.ofOpenSimulated()).thenReturn(open);

        assertThat(journal.openSimulatedTrades(USER))
                .extracting(OpenTrade::planId)
                .containsExactly(PLAN);
        assertThat(journal.openLevelChanges()).isEqualTo(open);
    }

    private static SimulatedLevelChange change(UUID positionId, boolean widens) {
        return new SimulatedLevelChange(
                positionId,
                3,
                new BigDecimal("95"),
                new BigDecimal("110"),
                new BigDecimal("93"),
                new BigDecimal("110"),
                widens,
                NOW.minusSeconds(30));
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
}
