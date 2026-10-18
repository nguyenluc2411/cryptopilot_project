package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.dto.response.CoinPnlResponse;
import com.cryptopilot.paper.dto.response.EquityResponse;
import com.cryptopilot.paper.dto.response.SpotPnlResponse;
import com.cryptopilot.paper.entity.PaperAccount;
import com.cryptopilot.paper.entity.PaperBalance;
import com.cryptopilot.paper.entity.PaperEquitySnapshot;
import com.cryptopilot.paper.model.enums.WalletType;
import com.cryptopilot.paper.repository.PaperAccountRepository;
import com.cryptopilot.paper.repository.PaperBalanceRepository;
import com.cryptopilot.paper.repository.PaperEquitySnapshotRepository;
import com.cryptopilot.paper.service.PaperPnlService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Spot PnL of TR-13 as Binance shows it: each coin's average cost (what the buys paid, the fees taken in the coin
 * included, less the share the sales took away), its value at the current last price, and the difference; and the
 * account's worth now against its snapshot at the start of the day.
 *
 * <p>The snapshots are taken once a day for every account, each in its own transaction, so one account that fails
 * does not keep the others from theirs. An account with a coin that has no current price gets none, and today's PnL
 * stays unknown until a retry of the same day takes it.
 *
 * <p>Rule: TR-13.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperPnlServiceImpl implements PaperPnlService {

    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 365;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Logger log = LoggerFactory.getLogger(PaperPnlServiceImpl.class);

    private final PaperAccountRepository accounts;
    private final PaperBalanceRepository balances;
    private final PaperEquitySnapshotRepository snapshots;
    private final PaperEquity equity;
    private final MarketApi market;
    private final TransactionTemplate transactions;

    @Override
    @Transactional(readOnly = true)
    public SpotPnlResponse spotPnl(UUID userId) {
        PaperAccount account = accountOf(userId);
        List<PaperBalance> held = balances.findByAccountIdAndWalletType(account.getId(), WalletType.SPOT).stream()
                .filter(balance -> balance.total().signum() > 0)
                .toList();
        Map<UUID, String> assets =
                market.coins(held.stream().map(PaperBalance::getCoinId).collect(Collectors.toSet())).stream()
                        .collect(Collectors.toMap(CoinListing::coinId, CoinListing::symbol, (a, b) -> a));
        List<PaperBalance> coins = held.stream()
                .filter(balance -> !PaperAccountServiceImpl.USDT.equals(assets.get(balance.getCoinId())))
                .toList();
        Set<String> pairs = coins.stream()
                .map(balance -> assets.get(balance.getCoinId()) + PaperAccountServiceImpl.USDT)
                .collect(Collectors.toSet());
        Map<String, BigDecimal> prices = pairs.isEmpty() ? Map.of() : market.currentLastPrices(MarketType.SPOT, pairs);
        List<CoinPnlResponse> rows = new ArrayList<>();
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalPnl = BigDecimal.ZERO;
        boolean complete = true;
        for (PaperBalance balance : coins) {
            String asset = assets.get(balance.getCoinId());
            CoinPnlResponse row = rowOf(balance, asset, prices.get(asset + PaperAccountServiceImpl.USDT));
            rows.add(row);
            if (row.value() != null) {
                totalValue = totalValue.add(row.value());
            }
            if (row.unrealizedPnl() != null) {
                totalPnl = totalPnl.add(row.unrealizedPnl());
            } else {
                complete = false;
            }
        }
        rows.sort(Comparator.comparing(
                        CoinPnlResponse::value, Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder()))
                .thenComparing(CoinPnlResponse::asset));
        return new SpotPnlResponse(
                List.copyOf(rows), Rounding.toAmount(totalValue), Rounding.toAmount(totalPnl), complete);
    }

    @Override
    @Transactional(readOnly = true)
    public EquityResponse equity(UUID userId, Integer days) {
        int period = days == null ? DEFAULT_DAYS : days;
        if (period < 1 || period > MAX_DAYS) {
            throw new FieldValidationException(
                    "days must be between 1 and " + MAX_DAYS + ", was " + period, Map.of("days", "MSG01"));
        }
        PaperAccount account = accountOf(userId);
        PaperEquity.Worth now = equity.worth(account.getId());
        LocalDate today = equity.today();
        Optional<PaperEquitySnapshot> start = snapshots.findByAccountIdAndSnapshotDate(account.getId(), today);
        BigDecimal todayPnl = start.map(
                        snapshot -> Rounding.toAmount(now.total().subtract(snapshot.getTotalEquityAmount())))
                .orElse(null);
        BigDecimal todayPercent = start.filter(
                        snapshot -> snapshot.getTotalEquityAmount().signum() > 0)
                .map(snapshot ->
                        todayPnl.multiply(HUNDRED).divide(snapshot.getTotalEquityAmount(), 2, RoundingMode.HALF_EVEN))
                .orElse(null);
        List<EquityResponse.Point> history = snapshots
                .findByAccountIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(
                        account.getId(), today.minusDays(period - 1L))
                .stream()
                .map(snapshot -> new EquityResponse.Point(
                        snapshot.getSnapshotDate(),
                        snapshot.getSpotEquityAmount(),
                        snapshot.getFuturesEquityAmount(),
                        snapshot.getTotalEquityAmount()))
                .toList();
        return new EquityResponse(now.spot(), now.futures(), now.total(), todayPnl, todayPercent, history);
    }

    @Override
    public int snapshotAll() {
        int taken = 0;
        for (UUID accountId : accounts.findIdsWithoutSnapshotOn(equity.today())) {
            try {
                Boolean took = transactions.execute(status -> equity.snapshot(accountId));
                if (Boolean.TRUE.equals(took)) {
                    taken++;
                }
            } catch (RuntimeException failure) {
                log.warn("TR-13 equity snapshot of paper account {} failed: {}", accountId, failure.toString());
            }
        }
        return taken;
    }

    /** One coin's cost, value and unrealised PnL; the parts that need a cost or a price are absent without it. */
    private static CoinPnlResponse rowOf(PaperBalance balance, String asset, BigDecimal price) {
        BigDecimal quantity = balance.total();
        BigDecimal cost = balance.getCostBasisAmount();
        BigDecimal value = price == null ? null : Rounding.toAmount(quantity.multiply(price));
        BigDecimal pnl = value == null || cost == null ? null : Rounding.toAmount(value.subtract(cost));
        BigDecimal percent = pnl == null || cost.signum() == 0
                ? null
                : pnl.multiply(HUNDRED).divide(cost, 2, RoundingMode.HALF_EVEN);
        return new CoinPnlResponse(
                balance.getCoinId(),
                asset,
                Rounding.toAmount(quantity),
                balance.averageCost(),
                cost == null ? null : Rounding.toAmount(cost),
                price,
                value,
                pnl,
                percent);
    }

    /** The caller's account; MSG41 when it is not opened yet. */
    private PaperAccount accountOf(UUID userId) {
        return accounts.findByUserId(userId).orElseThrow(() -> new ResourceNotFoundException("PaperAccount", userId));
    }
}
