package com.cryptopilot.paper.service.impl;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.common.util.TimeBounds;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.calculator.FuturesMarginMath;
import com.cryptopilot.paper.dto.response.FuturesAccountResponse;
import com.cryptopilot.paper.dto.response.FuturesBalanceResponse;
import com.cryptopilot.paper.dto.response.FuturesIncomeResponse;
import com.cryptopilot.paper.dto.response.FuturesPositionHistoryResponse;
import com.cryptopilot.paper.dto.response.FuturesPositionRiskResponse;
import com.cryptopilot.paper.entity.FuturesPosition;
import com.cryptopilot.paper.entity.PaperLedgerEntry;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.model.FuturesAccountMargin;
import com.cryptopilot.paper.model.FuturesCrossState;
import com.cryptopilot.paper.model.enums.LedgerEntryType;
import com.cryptopilot.paper.model.enums.LedgerRefType;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.PositionDirection;
import com.cryptopilot.paper.model.enums.PositionSide;
import com.cryptopilot.paper.model.enums.PositionStatus;
import com.cryptopilot.paper.repository.FuturesPositionRepository;
import com.cryptopilot.paper.repository.PaperAccountRepository;
import com.cryptopilot.paper.repository.PaperLedgerEntryRepository;
import com.cryptopilot.paper.service.FuturesAccountService;
import com.cryptopilot.paper.service.impl.FuturesMargins.Pending;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The paper Futures account and positions at the current mark prices (TR-18), every figure derived from the wallet,
 * the positions and the open orders, as nothing is locked:
 *
 * <ul>
 *   <li>A position's initial margin is {@code |notional| / leverage} at the mark, its maintenance margin
 *       {@code |notional| × MMR − cum} of its bracket (4.1); the break-even price is the entry while fees are zero.
 *   <li>The liquidation price of an isolated position uses its own wallet; of a cross one the cross wallet, less the
 *       maintenance margin and plus the unrealized profit of the other cross positions (4.5).
 *   <li>The margin ratio is maintenance over margin balance: the position's when isolated, the cross side's otherwise
 *       (4.10).
 *   <li>Available balance and the most that can be withdrawn follow 4.7.
 * </ul>
 *
 * <p>Without a mark price of a pair with a position nothing can be valued, and the read answers
 * {@code MARKET_PRICE_UNAVAILABLE}.
 *
 * <p>Rule: TR-18, TR-19; BR-26, BR-27; D-127.
 * <p>Reference: Binance. <i>USDⓈ-M Futures API: Position Information V3, Account Information V3, Futures Account
 * Balance V3</i>, Binance Developer Center.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FuturesAccountServiceImpl implements FuturesAccountService {

    private static final String ACCOUNT_ALIAS = "paper";

    /**
     * The exchange's income type of each ledger type of the Futures wallet (plan 7.2): the opening grant reads as a
     * transfer in, the clearance fee of a liquidation as INSURANCE_CLEAR, and INSURANCE_COVER is CryptoPilot's own.
     */
    private static final Map<LedgerEntryType, String> INCOME = Map.of(
            LedgerEntryType.INITIAL_GRANT, "TRANSFER",
            LedgerEntryType.TRANSFER, "TRANSFER",
            LedgerEntryType.REALIZED_PNL, "REALIZED_PNL",
            LedgerEntryType.FEE, "COMMISSION",
            LedgerEntryType.FUNDING_FEE, "FUNDING_FEE",
            LedgerEntryType.LIQUIDATION_FEE, "INSURANCE_CLEAR",
            LedgerEntryType.INSURANCE_COVER, "INSURANCE_COVER");

    /** The tick of a price column, for a pair whose filters are no longer read. */
    private static final BigDecimal FINEST_TICK = BigDecimal.ONE.movePointLeft(FuturesMarginMath.PRICE_SCALE);

    private final PaperAccountRepository accounts;
    private final FuturesPositionRepository positions;
    private final PaperLedgerEntryRepository ledger;
    private final FuturesMargins margins;
    private final MarketApi market;
    private final EntitlementApi entitlements;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<FuturesPositionRiskResponse> positionRisk(UUID userId, String symbol) {
        requireFutures(userId);
        UUID pairId = symbol == null ? null : margins.pair(symbol).pairId();
        Snapshot snapshot = snapshot(userId);
        List<FuturesPositionRiskResponse> risks = new ArrayList<>();
        for (Valued valued : snapshot.positions()) {
            if (pairId == null || valued.position().getPairId().equals(pairId)) {
                risks.add(risk(valued, snapshot));
            }
        }
        return risks;
    }

    @Override
    @Transactional(readOnly = true)
    public FuturesAccountResponse account(UUID userId) {
        requireFutures(userId);
        return accountOf(userId);
    }

    /**
     * The account at the current marks, without the plan's check: for the push of a change the Trader just made
     * (TR-41). Public, so a call through the transactional proxy reaches this bean.
     */
    public FuturesAccountResponse accountOf(UUID userId) {
        Snapshot snapshot = snapshot(userId);
        FuturesCrossState cross = snapshot.cross();
        FuturesAccountMargin margin = cross.margin();
        String asset = margins.usdt().symbol();
        BigDecimal unrealized = sum(snapshot, Valued::unrealized);
        BigDecimal positionInitial = sum(snapshot, Valued::initial);
        BigDecimal maintenance = sum(snapshot, Valued::maintenance);
        BigDecimal initial = positionInitial.add(cross.openOrderInitialMargin());
        BigDecimal marginBalance = cross.walletBalance().add(unrealized);
        Instant now = clock.instant();
        List<FuturesAccountResponse.Position> open = snapshot.positions().stream()
                .map(valued -> new FuturesAccountResponse.Position(
                        valued.symbol(),
                        valued.position().getPositionSide(),
                        valued.position().signedQuantity(),
                        valued.unrealized(),
                        isolatedMargin(valued),
                        valued.notional(),
                        isolatedWallet(valued),
                        valued.initial().add(snapshot.orderMargin(valued)),
                        valued.maintenance(),
                        valued.position().getUpdatedAt()))
                .toList();
        FuturesAccountResponse.Asset usdt = new FuturesAccountResponse.Asset(
                asset,
                cross.walletBalance(),
                unrealized,
                marginBalance,
                maintenance,
                initial,
                positionInitial,
                cross.openOrderInitialMargin(),
                cross.crossWalletBalance(),
                cross.crossUnrealizedPnl(),
                margin.availableBalance(),
                margin.maxWithdrawAmount(),
                now);
        return new FuturesAccountResponse(
                initial,
                maintenance,
                cross.walletBalance(),
                unrealized,
                marginBalance,
                positionInitial,
                cross.openOrderInitialMargin(),
                cross.crossWalletBalance(),
                cross.crossUnrealizedPnl(),
                margin.availableBalance(),
                margin.maxWithdrawAmount(),
                snapshot.crossRatio(),
                List.of(usdt),
                open);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FuturesBalanceResponse> balance(UUID userId) {
        requireFutures(userId);
        FuturesCrossState cross = snapshot(userId).cross();
        FuturesAccountMargin margin = cross.margin();
        return List.of(new FuturesBalanceResponse(
                ACCOUNT_ALIAS,
                margins.usdt().symbol(),
                cross.walletBalance(),
                cross.crossWalletBalance(),
                cross.crossUnrealizedPnl(),
                margin.availableBalance(),
                margin.maxWithdrawAmount(),
                true,
                clock.instant()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FuturesIncomeResponse> income(
            UUID userId, String symbol, String incomeType, Instant startTime, Instant endTime, Integer limit) {
        requireFutures(userId);
        TimeBounds.requireOrdered(startTime, endTime);
        Set<LedgerEntryType> types = incomeType == null ? INCOME.keySet() : typesOf(incomeType);
        PageRequest page = PageRequest.of(0, limitOf(limit, 100));
        Instant from = TimeBounds.fromOrEarliest(startTime);
        Instant to = TimeBounds.toOrLatest(endTime);
        List<PaperLedgerEntry> found = new ArrayList<>(
                symbol == null
                        ? ledger.searchFutures(userId, types, from, to, page)
                        : ledger.searchFuturesPair(userId, margins.pair(symbol).pairId(), types, from, to, page));
        Collections.reverse(found);
        Map<UUID, String> symbols = symbolsOf(found.stream()
                .map(PaperLedgerEntry::getPairId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        String asset = margins.usdt().symbol();
        return found.stream()
                .map(entry -> new FuturesIncomeResponse(
                        entry.getPairId() == null ? "" : symbols.get(entry.getPairId()),
                        INCOME.get(entry.getEntryType()),
                        entry.getAmount(),
                        asset,
                        entry.getEntryType().name(),
                        entry.getCreatedAt(),
                        entry.getId(),
                        entry.getRefType() == LedgerRefType.FILL ? entry.getRefId() : null))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FuturesPositionHistoryResponse> positionHistory(
            UUID userId, String symbol, Instant startTime, Instant endTime, Integer limit) {
        requireFutures(userId);
        TimeBounds.requireOrdered(startTime, endTime);
        PageRequest page = PageRequest.of(0, limitOf(limit, 100));
        Instant from = TimeBounds.fromOrEarliest(startTime);
        Instant to = TimeBounds.toOrLatest(endTime);
        List<FuturesPosition> closed = symbol == null
                ? positions.closed(userId, from, to, page)
                : positions.closedOfPair(userId, margins.pair(symbol).pairId(), from, to, page);
        Map<UUID, String> symbols =
                symbolsOf(closed.stream().map(FuturesPosition::getPairId).collect(Collectors.toSet()));
        return closed.stream()
                .map(position -> new FuturesPositionHistoryResponse(
                        position.getId(),
                        symbols.get(position.getPairId()),
                        position.getPositionSide(),
                        position.getDirection(),
                        position.getMarginType(),
                        position.getLeverage(),
                        position.getMaxQuantity(),
                        position.getClosedQuantity(),
                        position.getEntryPrice(),
                        position.getCloseAvgPrice(),
                        position.getRealizedPnlAmount(),
                        position.getFundingAmount(),
                        position.getLiquidationFeeAmount(),
                        position.getRealizedPnlAmount()
                                .add(position.getFundingAmount())
                                .subtract(position.getLiquidationFeeAmount()),
                        position.getCloseReason(),
                        position.getOpenedAt(),
                        position.getClosedAt()))
                .toList();
    }

    /** The ledger types an income type stands for; an unknown one is refused. */
    private static Set<LedgerEntryType> typesOf(String incomeType) {
        Set<LedgerEntryType> types = INCOME.entrySet().stream()
                .filter(entry -> entry.getValue().equals(incomeType))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        if (types.isEmpty()) {
            throw new FieldValidationException(
                    "incomeType " + incomeType + " is not one of " + Set.copyOf(INCOME.values()),
                    Map.of("incomeType", "MSG15"));
        }
        return types;
    }

    private Map<UUID, String> symbolsOf(Set<UUID> pairIds) {
        Map<UUID, String> symbols = new HashMap<>();
        if (!pairIds.isEmpty()) {
            market.pairListings(pairIds).forEach(pair -> symbols.put(pair.pairId(), pair.symbol()));
        }
        return symbols;
    }

    private static int limitOf(Integer limit, int fallback) {
        if (limit == null) {
            return fallback;
        }
        if (limit < 1 || limit > 1000) {
            throw new FieldValidationException("limit must be 1 to 1000", Map.of("limit", "MSG15"));
        }
        return limit;
    }

    /**
     * Values every open position at its mark, the open orders of its pair and the cross side of the account.
     *
     * <p>Reference: Hull, J. C. (2022). <i>Options, Futures, and Other Derivatives</i> (11th ed.). Pearson, ch. 2
     * (margin accounts marked to market).
     */
    private Snapshot snapshot(UUID userId) {
        List<FuturesPosition> open = positions.findByUserIdAndPositionStatus(userId, PositionStatus.OPEN).stream()
                .sorted(Comparator.comparing(FuturesPosition::getOpenedAt).thenComparing(FuturesPosition::getId))
                .toList();
        Map<UUID, BigDecimal> marks = margins.marks(open);
        Map<UUID, List<Pending>> pending = new LinkedHashMap<>();
        for (PaperOrder order : margins.openOrders(userId)) {
            pending.computeIfAbsent(order.getPairId(), ignored -> new ArrayList<>())
                    .add(Pending.of(order));
        }
        Set<UUID> pairIds = new HashSet<>(marks.keySet());
        Map<UUID, String> symbols = new LinkedHashMap<>();
        for (PairListing pair : market.pairListings(pairIds)) {
            symbols.put(pair.pairId(), pair.symbol());
        }
        List<Valued> valued = new ArrayList<>(open.size());
        Map<UUID, BigDecimal> orderMargins = new LinkedHashMap<>();
        for (FuturesPosition position : open) {
            UUID pairId = position.getPairId();
            BigDecimal mark = marks.get(pairId);
            List<LeverageTier> tiers = market.leverageBrackets(pairId);
            BigDecimal notional = position.signedQuantity().multiply(mark);
            BigDecimal tick = market.tradablePair(pairId, MarketType.FUTURES)
                    .map(TradablePair::filters)
                    .map(PairFilters::tickSize)
                    .orElse(FINEST_TICK);
            valued.add(new Valued(
                    position,
                    symbols.get(pairId),
                    mark,
                    Rounding.toAmount(notional),
                    FuturesMarginMath.unrealizedPnl(
                            position.getDirection(), position.getQuantity(), position.getEntryPrice(), mark),
                    FuturesMarginMath.initialMargin(notional.abs(), position.getLeverage()),
                    maintenance(tiers, notional),
                    tiers,
                    tick));
            // A hedge position is margined with its own side's orders only (TR-14b).
            List<Pending> orders = pending.getOrDefault(pairId, List.of()).stream()
                    .filter(order -> order.positionSide() == position.getPositionSide())
                    .toList();
            orderMargins.put(
                    position.getId(),
                    FuturesMargins.pairOrderMargin(
                            position.signedQuantity(),
                            orders,
                            position.getLeverage(),
                            market.currentLastPrice(MarketType.FUTURES, symbols.get(pairId))
                                    .orElse(mark),
                            mark));
        }
        FuturesCrossState cross = margins.crossState(userId, open, marks, margins.openOrderMargin(userId, open, null));
        BigDecimal crossMaintenance = BigDecimal.ZERO;
        for (Valued position : valued) {
            if (position.cross()) {
                crossMaintenance = crossMaintenance.add(position.maintenance());
            }
        }
        BigDecimal crossRatio = FuturesMarginMath.marginRatio(
                        crossMaintenance, cross.crossWalletBalance().add(cross.crossUnrealizedPnl()))
                .orElse(null);
        return new Snapshot(valued, pending, orderMargins, cross, crossMaintenance, crossRatio);
    }

    private FuturesPositionRiskResponse risk(Valued valued, Snapshot snapshot) {
        FuturesPosition position = valued.position();
        BigDecimal orderMargin = snapshot.orderMargin(valued);
        BigDecimal liquidation;
        BigDecimal ratio;
        Optional<Valued> opposite = valued.cross() ? snapshot.hedgedOpposite(valued) : Optional.empty();
        if (opposite.isPresent()) {
            // A hedged LONG and SHORT in cross share one liquidation price (plan 4.5 note 2).
            FuturesCrossState cross = snapshot.cross();
            Valued longSide = position.getDirection() == PositionDirection.LONG ? valued : opposite.get();
            Valued shortSide = longSide == valued ? opposite.get() : valued;
            liquidation = FuturesMarginMath.hedgeLiquidationPrice(
                    longSide.position().getQuantity(),
                    longSide.position().getEntryPrice(),
                    shortSide.position().getQuantity(),
                    shortSide.position().getEntryPrice(),
                    cross.crossWalletBalance(),
                    snapshot.crossMaintenance()
                            .subtract(valued.maintenance())
                            .subtract(opposite.get().maintenance()),
                    cross.crossUnrealizedPnl()
                            .subtract(valued.unrealized())
                            .subtract(opposite.get().unrealized()),
                    valued.tiers(),
                    valued.tick());
            ratio = snapshot.crossRatio();
        } else if (valued.cross()) {
            FuturesCrossState cross = snapshot.cross();
            liquidation = FuturesMarginMath.liquidationPrice(
                    position.getDirection(),
                    position.getQuantity(),
                    position.getEntryPrice(),
                    cross.crossWalletBalance(),
                    snapshot.crossMaintenance().subtract(valued.maintenance()),
                    cross.crossUnrealizedPnl().subtract(valued.unrealized()),
                    valued.tiers(),
                    valued.tick());
            ratio = snapshot.crossRatio();
        } else {
            liquidation = FuturesMarginMath.liquidationPrice(
                    position.getDirection(),
                    position.getQuantity(),
                    position.getEntryPrice(),
                    position.getIsolatedWalletAmount(),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    valued.tiers(),
                    valued.tick());
            ratio = FuturesMarginMath.marginRatio(valued.maintenance(), isolatedMargin(valued))
                    .orElse(null);
        }
        List<Pending> orders = snapshot.pending().getOrDefault(position.getPairId(), List.of());
        return new FuturesPositionRiskResponse(
                valued.symbol(),
                position.getPositionSide(),
                position.signedQuantity(),
                position.getEntryPrice(),
                position.getEntryPrice(),
                valued.mark(),
                valued.unrealized(),
                liquidation,
                isolatedMargin(valued),
                valued.notional(),
                margins.usdt().symbol(),
                isolatedWallet(valued),
                valued.initial().add(orderMargin),
                valued.maintenance(),
                valued.initial(),
                orderMargin,
                0,
                openingNotional(orders, OrderSide.BUY),
                openingNotional(orders, OrderSide.SELL),
                position.getMarginType(),
                position.getLeverage(),
                ratio,
                position.getUpdatedAt());
    }

    /** The value at their limit prices of a side's open orders that are not reduce-only. */
    private static BigDecimal openingNotional(List<Pending> orders, OrderSide side) {
        BigDecimal notional = BigDecimal.ZERO;
        for (Pending order : orders) {
            if (order.side() == side && !order.reduceOnly() && order.limitPrice() != null) {
                notional = notional.add(order.limitPrice().multiply(order.quantity()));
            }
        }
        return Rounding.toAmount(notional);
    }

    /** The maintenance margin by the notional's bracket, the largest bracket's above every cap. */
    private static BigDecimal maintenance(List<LeverageTier> tiers, BigDecimal notional) {
        List<LeverageTier> resolved = FuturesMarginMath.withMaintenanceAmounts(tiers);
        LeverageTier tier = FuturesMarginMath.bracketFor(resolved, notional).orElse(resolved.getLast());
        return Rounding.toAmount(
                notional.abs().multiply(tier.maintenanceMarginRate()).subtract(tier.maintenanceAmount()));
    }

    private static BigDecimal isolatedMargin(Valued valued) {
        return valued.cross()
                ? BigDecimal.ZERO
                : Rounding.toAmount(valued.position().getIsolatedWalletAmount().add(valued.unrealized()));
    }

    private static BigDecimal isolatedWallet(Valued valued) {
        return valued.cross() ? BigDecimal.ZERO : valued.position().getIsolatedWalletAmount();
    }

    private static BigDecimal sum(Snapshot snapshot, Function<Valued, BigDecimal> figure) {
        return snapshot.positions().stream().map(figure).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The Futures feature of the Trader's plan (Q-T10) and an opened paper account (MSG41). */
    private void requireFutures(UUID userId) {
        entitlements.requireFeature(userId, Feature.FUTURES_ANALYSIS);
        accounts.findByUserId(userId).orElseThrow(() -> new ResourceNotFoundException("PaperAccount", userId));
    }

    /** An open position valued at its mark. */
    private record Valued(
            FuturesPosition position,
            String symbol,
            BigDecimal mark,
            BigDecimal notional,
            BigDecimal unrealized,
            BigDecimal initial,
            BigDecimal maintenance,
            List<LeverageTier> tiers,
            BigDecimal tick) {

        boolean cross() {
            return position.getMarginType() == MarginType.CROSSED;
        }
    }

    /** The positions valued, the open orders by pair, their margin by pair, and the cross side. */
    private record Snapshot(
            List<Valued> positions,
            Map<UUID, List<Pending>> pending,
            Map<UUID, BigDecimal> orderMargins,
            FuturesCrossState cross,
            BigDecimal crossMaintenance,
            BigDecimal crossRatio) {

        /** The other side of a hedged cross position on the same pair, when it is open and cross too. */
        Optional<Valued> hedgedOpposite(Valued valued) {
            FuturesPosition position = valued.position();
            if (position.getPositionSide() == PositionSide.BOTH) {
                return Optional.empty();
            }
            return positions().stream()
                    .filter(other -> other != valued
                            && other.cross()
                            && other.position().getPairId().equals(position.getPairId())
                            && other.position().getPositionSide() != position.getPositionSide())
                    .findFirst();
        }

        BigDecimal orderMargin(Valued valued) {
            return orderMargins.getOrDefault(valued.position().getId(), BigDecimal.ZERO);
        }
    }
}
