package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.dto.response.PnlAnalysisResponse;
import com.cryptopilot.paper.entity.PaperEquitySnapshot;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.enums.WalletType;
import com.cryptopilot.paper.repository.PaperAccountRepository;
import com.cryptopilot.paper.repository.PaperEquitySnapshotRepository;
import com.cryptopilot.paper.repository.PaperPnlQueries;
import com.cryptopilot.paper.service.PaperPnlAnalysisService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The PnL analysis of TR-28, read from what TR-13 already stores: the snapshot of each wallet's worth at the start of
 * each UTC day, the transfers between the wallets in the ledger, and the closed Futures positions.
 *
 * <ul>
 *   <li><b>A day's PnL</b> is the wallet's worth at the next day's start (now, for today) less its worth at the day's
 *       start, less what transfers brought into it that day: moving USDT from Spot to Futures is neither a loss of Spot
 *       nor a gain of Futures, and cancels out for both wallets together. A day without its own snapshot, or without
 *       the next day's, has no PnL and is left out rather than guessed.
 *   <li><b>The running total</b> adds the days' PnL in order; <b>the deepest fall</b> is the largest drop of the
 *       running total below its highest point so far (0 at the period's start), and its percent is of the worth at
 *       that point.
 *   <li><b>The record</b> counts each trade closed in the period once, by its net result: a Futures position (realised
 *       PnL with funding, less the liquidation fee and its fills' fees) and a Spot sale order (what it netted less the
 *       share of the average cost it took); a result of 0 is neither a win nor a loss.
 * </ul>
 *
 * <p>Rule: TR-28; plan section 8.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperPnlAnalysisServiceImpl implements PaperPnlAnalysisService {

    static final Set<Integer> PERIODS = Set.of(7, 30, 90);
    private static final int DEFAULT_DAYS = 30;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PaperAccountRepository accounts;
    private final PaperEquitySnapshotRepository snapshots;
    private final PaperPnlQueries queries;
    private final PaperEquity equity;

    @Override
    @Transactional(readOnly = true)
    public PnlAnalysisResponse analysis(UUID userId, MarketType market, Integer days) {
        int period = days == null ? DEFAULT_DAYS : days;
        if (!PERIODS.contains(period)) {
            throw new FieldValidationException("days must be 7, 30 or 90, was " + period, Map.of("days", "MSG01"));
        }
        PaperAccount account =
                accounts.findByUserId(userId).orElseThrow(() -> new ResourceNotFoundException("PaperAccount", userId));
        LocalDate today = equity.today();
        LocalDate first = today.minusDays(period - 1L);
        PaperEquity.Worth now = equity.worth(account.userId());

        Function<PaperEquitySnapshot, BigDecimal> worthOf = worthOf(market);
        Map<LocalDate, BigDecimal> starts =
                snapshots
                        .findByUserIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(account.userId(), first)
                        .stream()
                        .collect(Collectors.toMap(
                                PaperEquitySnapshot::getSnapshotDate, worthOf, (a, b) -> a, TreeMap::new));
        Map<LocalDate, BigDecimal> transfers = transfersOf(account.userId(), market, first);
        BigDecimal worthNow = market == null ? now.total() : market == MarketType.SPOT ? now.spot() : now.futures();

        List<PnlAnalysisResponse.Day> daily = new ArrayList<>();
        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal peakWorth = null;
        BigDecimal drawdown = BigDecimal.ZERO;
        BigDecimal drawdownBase = null;
        BigDecimal todayPnl = null;
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            BigDecimal start = starts.get(day);
            BigDecimal end = day.equals(today) ? worthNow : starts.get(day.plusDays(1));
            if (start == null || end == null) {
                continue;
            }
            if (peakWorth == null) {
                peakWorth = start;
            }
            BigDecimal pnl =
                    Rounding.toAmount(end.subtract(start).subtract(transfers.getOrDefault(day, BigDecimal.ZERO)));
            cumulative = cumulative.add(pnl);
            if (cumulative.compareTo(peak) > 0) {
                peak = cumulative;
                peakWorth = end;
            }
            BigDecimal fall = peak.subtract(cumulative);
            if (fall.compareTo(drawdown) > 0) {
                drawdown = fall;
                drawdownBase = peakWorth;
            }
            daily.add(new PnlAnalysisResponse.Day(day, pnl, cumulative));
            if (day.equals(today)) {
                todayPnl = pnl;
            }
        }
        BigDecimal drawdownPercent;
        if (daily.isEmpty()) {
            drawdownPercent = null;
        } else if (drawdown.signum() == 0) {
            drawdownPercent = BigDecimal.ZERO.setScale(2);
        } else if (drawdownBase != null && drawdownBase.signum() > 0) {
            drawdownPercent = drawdown.multiply(HUNDRED).divide(drawdownBase, 2, RoundingMode.HALF_EVEN);
        } else {
            drawdownPercent = null;
        }
        return new PnlAnalysisResponse(
                market == null ? "ALL" : market.name(),
                period,
                todayPnl,
                cumulative,
                drawdown,
                drawdownPercent,
                now.complete(),
                List.copyOf(daily),
                trades(account.userId(), market, first));
    }

    private static Function<PaperEquitySnapshot, BigDecimal> worthOf(MarketType market) {
        if (market == null) {
            return PaperEquitySnapshot::getTotalEquityAmount;
        }
        return market == MarketType.SPOT
                ? PaperEquitySnapshot::getSpotEquityAmount
                : PaperEquitySnapshot::getFuturesEquityAmount;
    }

    /** What transfers brought into the wallet asked for each day; nothing for both wallets, where they cancel. */
    private Map<LocalDate, BigDecimal> transfersOf(UUID userId, MarketType market, LocalDate first) {
        if (market == null) {
            return Map.of();
        }
        return queries.transfersIn(userId, market == MarketType.SPOT ? WalletType.SPOT : WalletType.FUTURES, first);
    }

    /** The trades of the wallet asked for: Spot sale orders, Futures positions, or both. */
    private PnlAnalysisResponse.Trades trades(UUID userId, MarketType market, LocalDate first) {
        Instant from = first.atStartOfDay().toInstant(ZoneOffset.UTC);
        List<PaperPnlQueries.ClosedTrade> closed = new ArrayList<>();
        if (market != MarketType.FUTURES) {
            closed.addAll(queries.spotSales(userId, from));
        }
        if (market != MarketType.SPOT) {
            closed.addAll(queries.closedPositions(userId, from));
        }
        BigDecimal won = BigDecimal.ZERO;
        BigDecimal lost = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        int wins = 0;
        int losses = 0;
        for (PaperPnlQueries.ClosedTrade trade : closed) {
            total = total.add(trade.net());
            if (trade.net().signum() > 0) {
                wins++;
                won = won.add(trade.net());
            } else if (trade.net().signum() < 0) {
                losses++;
                lost = lost.add(trade.net());
            }
        }
        return new PnlAnalysisResponse.Trades(
                closed.size(),
                wins,
                losses,
                closed.isEmpty() ? null : percent(wins, closed.size()),
                Rounding.toAmount(total),
                wins == 0 ? null : Rounding.toAmount(won.divide(BigDecimal.valueOf(wins), 8, RoundingMode.HALF_EVEN)),
                losses == 0
                        ? null
                        : Rounding.toAmount(lost.divide(BigDecimal.valueOf(losses), 8, RoundingMode.HALF_EVEN)),
                losses == 0 ? null : won.divide(lost.negate(), 2, RoundingMode.HALF_EVEN));
    }

    private static BigDecimal percent(int part, int whole) {
        return BigDecimal.valueOf(part).multiply(HUNDRED).divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_EVEN);
    }
}
