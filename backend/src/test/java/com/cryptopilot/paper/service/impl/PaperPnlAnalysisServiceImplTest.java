package com.cryptopilot.paper.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.dto.response.PnlAnalysisResponse;
import com.cryptopilot.paper.entity.PaperEquitySnapshot;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.enums.WalletType;
import com.cryptopilot.paper.repository.PaperAccountRepository;
import com.cryptopilot.paper.repository.PaperEquitySnapshotRepository;
import com.cryptopilot.paper.repository.PaperPnlQueries;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The figures of the PnL analysis (TR-28) from snapshots, transfers and closed positions.
 *
 * <p>Invariants: (1) a day's PnL is the next start (now for today) less its start, less the transfers in; (2) the
 * transfers cancel for both wallets; (3) a day missing either end is left out; (4) the running total is the sum of the
 * days; (5) the deepest fall is the largest drop below the highest running total so far, 0 at the start; (6) a closed
 * position of 0 is neither a win nor a loss; (7) the Spot analysis has no Futures record.
 *
 * <p>Rule: TR-28.
 */
class PaperPnlAnalysisServiceImplTest {

    private static final UUID TRADER = UUID.fromString("019b76da-a800-7000-8000-00000000f001");
    private static final LocalDate TODAY = LocalDate.parse("2026-11-22");

    private final PaperAccountRepository accounts = mock(PaperAccountRepository.class);
    private final PaperEquitySnapshotRepository snapshots = mock(PaperEquitySnapshotRepository.class);
    private final PaperPnlQueries queries = mock(PaperPnlQueries.class);
    private final PaperEquity equity = mock(PaperEquity.class);
    private final PaperPnlAnalysisServiceImpl service =
            new PaperPnlAnalysisServiceImpl(accounts, snapshots, queries, equity);
    private final List<PaperEquitySnapshot> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(accounts.findByUserId(TRADER))
                .thenReturn(Optional.of(new PaperAccount(TRADER, Instant.parse("2026-11-01T00:00:00Z"))));
        when(equity.today()).thenReturn(TODAY);
        when(snapshots.findByUserIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(eq(TRADER), any()))
                .thenAnswer(call -> stored.stream()
                        .filter(s -> !s.getSnapshotDate().isBefore(call.getArgument(1)))
                        .toList());
        when(queries.transfersIn(any(), any(), any())).thenReturn(Map.of());
        when(queries.closedPositions(any(), any())).thenReturn(List.of());
        when(queries.spotSales(any(), any())).thenReturn(List.of());
        now("10000", "5000");
    }

    @Test
    void TR28_eachDay_isItsChangeLessTheTransfersIn_andTheRunningTotalAndFallFollow() {
        snapshot(2, "10000", "5000");
        snapshot(1, "10000", "5100");
        snapshot(0, "10000", "5050");
        now("9900", "5100");
        when(queries.transfersIn(TRADER, WalletType.FUTURES, TODAY.minusDays(6)))
                .thenReturn(Map.of(TODAY, new BigDecimal("100")));

        PnlAnalysisResponse futures = service.analysis(TRADER, MarketType.FUTURES, 7);

        assertThat(futures.market()).isEqualTo("FUTURES");
        assertThat(futures.daily())
                .extracting(
                        PnlAnalysisResponse.Day::date,
                        d -> d.pnl().stripTrailingZeros().toPlainString(),
                        d -> d.cumulativePnl().stripTrailingZeros().toPlainString())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(TODAY.minusDays(2), "100", "100"),
                        org.assertj.core.groups.Tuple.tuple(TODAY.minusDays(1), "-50", "50"),
                        org.assertj.core.groups.Tuple.tuple(TODAY, "-50", "0"));
        assertThat(futures.todayPnl()).isEqualByComparingTo("-50");
        assertThat(futures.cumulativePnl()).isEqualByComparingTo("0");
        assertThat(futures.maxDrawdown()).isEqualByComparingTo("100");
        assertThat(futures.maxDrawdownPercent()).isEqualByComparingTo("1.96");
        assertThat(futures.complete()).isTrue();
        assertThat(futures.trades()).isNotNull();
    }

    @Test
    void TR28_bothWallets_letTheTransfersCancel() {
        snapshot(0, "10000", "5050");
        now("9900", "5100");

        PnlAnalysisResponse all = service.analysis(TRADER, null, null);

        assertThat(all.market()).isEqualTo("ALL");
        assertThat(all.days()).isEqualTo(30);
        assertThat(all.todayPnl()).isEqualByComparingTo("-50");
        verify(queries, never()).transfersIn(any(), any(), any());
    }

    @Test
    void TR28_aDayWithoutEitherEnd_isLeftOut() {
        snapshot(3, "10000", "5000");
        snapshot(1, "10200", "5000");

        PnlAnalysisResponse spot = service.analysis(TRADER, MarketType.SPOT, 7);

        assertThat(spot.daily()).isEmpty();
        assertThat(spot.todayPnl()).isNull();
        assertThat(spot.cumulativePnl()).isEqualByComparingTo("0");
        assertThat(spot.maxDrawdownPercent()).isNull();
        assertThat(spot.trades().closed()).as("no Spot sale realised").isZero();
    }

    @Test
    void TR28_noFall_isZeroPercent_andAnUnpricedCoin_isReported() {
        snapshot(1, "10000", "5000");
        snapshot(0, "10100", "5000");
        when(equity.worth(TRADER))
                .thenReturn(new PaperEquity.Worth(new BigDecimal("10150"), new BigDecimal("5000"), Set.of("BNB")));

        PnlAnalysisResponse spot = service.analysis(TRADER, MarketType.SPOT, 7);

        assertThat(spot.maxDrawdown()).isEqualByComparingTo("0");
        assertThat(spot.maxDrawdownPercent()).isEqualByComparingTo("0.00");
        assertThat(spot.complete()).isFalse();
        // BNB is left out of the worth now: today is not known, so it is not counted (no loss and no gain).
        assertThat(spot.todayPnl()).isNull();
        assertThat(spot.daily()).hasSize(1);
        assertThat(spot.cumulativePnl()).isEqualByComparingTo("100");

        PnlAnalysisResponse all = service.analysis(TRADER, null, 7);
        assertThat(all.todayPnl()).isNull();
        assertThat(all.cumulativePnl()).isEqualByComparingTo("100");

        // The Futures wallet holds no coin: its day is known.
        PnlAnalysisResponse futures = service.analysis(TRADER, MarketType.FUTURES, 7);
        assertThat(futures.todayPnl()).isEqualByComparingTo("0");
        assertThat(futures.daily()).hasSize(2);
        assertThat(futures.complete()).isTrue();
    }

    @Test
    void TR28_theFuturesRecord_countsEachClosedPositionByItsNetResult() {
        now("10000", "5000");
        when(queries.closedPositions(eq(TRADER), any()))
                .thenReturn(List.of(trade("30"), trade("-10"), trade("0"), trade("50"), trade("-20")));

        PnlAnalysisResponse.Trades trades =
                service.analysis(TRADER, MarketType.FUTURES, 30).trades();

        assertThat(trades.closed()).isEqualTo(5);
        assertThat(trades.wins()).isEqualTo(2);
        assertThat(trades.losses()).isEqualTo(2);
        assertThat(trades.winRate()).isEqualByComparingTo("40.00");
        assertThat(trades.totalPnl()).isEqualByComparingTo("50");
        assertThat(trades.averageWin()).isEqualByComparingTo("40");
        assertThat(trades.averageLoss()).isEqualByComparingTo("-15");
        assertThat(trades.profitFactor()).isEqualByComparingTo("2.67");
    }

    @Test
    void TR28_noClosedPosition_orNoLoss_leavesTheRatiosUnknown() {
        now("10000", "5000");
        PnlAnalysisResponse.Trades none = service.analysis(TRADER, null, 7).trades();
        when(queries.closedPositions(eq(TRADER), any())).thenReturn(List.of(trade("10")));
        PnlAnalysisResponse.Trades onlyWins = service.analysis(TRADER, null, 7).trades();

        assertThat(none.closed()).isZero();
        assertThat(none.winRate()).isNull();
        assertThat(none.averageWin()).isNull();
        assertThat(none.profitFactor()).isNull();
        assertThat(onlyWins.winRate()).isEqualByComparingTo("100.00");
        assertThat(onlyWins.averageLoss()).isNull();
        assertThat(onlyWins.profitFactor()).isNull();
    }

    @Test
    void TR28_theSpotRecord_countsTheSales_andBothWallets_countEverything() {
        when(queries.spotSales(eq(TRADER), any())).thenReturn(List.of(trade("12"), trade("-4")));
        when(queries.closedPositions(eq(TRADER), any())).thenReturn(List.of(trade("30")));

        PnlAnalysisResponse.Trades spot =
                service.analysis(TRADER, MarketType.SPOT, 7).trades();
        PnlAnalysisResponse.Trades futures =
                service.analysis(TRADER, MarketType.FUTURES, 7).trades();
        PnlAnalysisResponse.Trades all = service.analysis(TRADER, null, 7).trades();

        assertThat(spot.closed()).isEqualTo(2);
        assertThat(spot.profitFactor()).isEqualByComparingTo("3.00");
        assertThat(futures.closed()).isEqualTo(1);
        assertThat(all.closed()).isEqualTo(3);
        assertThat(all.totalPnl()).isEqualByComparingTo("38");
    }

    @ParameterizedTest(name = "{0} days")
    @ValueSource(ints = {0, 1, 14, 31, 365})
    void TR28_aPeriodOtherThan7_30Or90_isRefused(int days) {
        assertThatExceptionOfType(FieldValidationException.class)
                .isThrownBy(() -> service.analysis(TRADER, null, days))
                .satisfies(e -> assertThat(e.errors()).containsEntry("days", "MSG01"));
    }

    @Test
    void TR28_anAccountNotOpened_isNotFound() {
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.analysis(UUID.randomUUID(), null, 7));
    }

    private void snapshot(int daysAgo, String spot, String futures) {
        PaperEquitySnapshot snapshot = mock(PaperEquitySnapshot.class);
        BigDecimal s = new BigDecimal(spot);
        BigDecimal f = new BigDecimal(futures);
        when(snapshot.getSnapshotDate()).thenReturn(TODAY.minusDays(daysAgo));
        when(snapshot.getSpotEquityAmount()).thenReturn(s);
        when(snapshot.getFuturesEquityAmount()).thenReturn(f);
        when(snapshot.getTotalEquityAmount()).thenReturn(s.add(f));
        stored.add(snapshot);
        stored.sort(java.util.Comparator.comparing(PaperEquitySnapshot::getSnapshotDate));
    }

    private void now(String spot, String futures) {
        when(equity.worth(TRADER))
                .thenReturn(new PaperEquity.Worth(new BigDecimal(spot), new BigDecimal(futures), Set.of()));
    }

    private static PaperPnlQueries.ClosedTrade trade(String net) {
        return new PaperPnlQueries.ClosedTrade(
                UUID.randomUUID(), Instant.parse("2026-11-20T10:00:00Z"), new BigDecimal(net));
    }
}
