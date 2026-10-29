package com.cryptopilot.paper.service.impl;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.common.util.TimeBounds;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.paper.calculator.FuturesMarginMath;
import com.cryptopilot.paper.dto.request.FuturesClosePositionRequest;
import com.cryptopilot.paper.dto.request.FuturesOrderRequest;
import com.cryptopilot.paper.dto.request.FuturesPositionMarginRequest;
import com.cryptopilot.paper.dto.response.FuturesMarginHistoryResponse;
import com.cryptopilot.paper.dto.response.FuturesOrderResponse;
import com.cryptopilot.paper.dto.response.FuturesPositionMarginResponse;
import com.cryptopilot.paper.entity.FuturesAccountConfig;
import com.cryptopilot.paper.entity.FuturesMarginAdjustment;
import com.cryptopilot.paper.entity.FuturesPosition;
import com.cryptopilot.paper.exception.FuturesRejection;
import com.cryptopilot.paper.model.enums.MarginAdjustmentType;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.PositionDirection;
import com.cryptopilot.paper.model.enums.PositionSide;
import com.cryptopilot.paper.model.enums.PositionStatus;
import com.cryptopilot.paper.model.enums.TimeInForce;
import com.cryptopilot.paper.repository.FuturesMarginAdjustmentRepository;
import com.cryptopilot.paper.repository.FuturesPositionRepository;
import com.cryptopilot.paper.repository.PaperAccountRepository;
import com.cryptopilot.paper.service.FuturesOrderService;
import com.cryptopilot.paper.service.FuturesPositionService;
import com.cryptopilot.paper.service.impl.FuturesMargins.FuturesPair;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The position actions of TR-17, one-way mode.
 *
 * <h2>Margin of an isolated position (4.9)</h2>
 *
 * <p>Margin added comes from the cross wallet's free cash, unrealized profit excluded (4.3 rule 6), and is refused
 * past it ({@code -2019}); margin taken out is at most {@code max(0, min(IW, IW + uPnL − notional at mark / L))},
 * so what is left still covers the initial margin at the mark ({@code -4051}). A cross position has no margin of its
 * own ({@code -4049}), a pair without a position none to change ({@code -4054}). Every change is one
 * {@code futures_margin_adjustment} row, MANUAL_ADD or MANUAL_REDUCE, and writes no ledger entry, as the Futures
 * wallet balance does not change (4.3 rule 2).
 *
 * <h2>Closing</h2>
 *
 * <p>A close is a reduce-only order on the other side of the position, placed as any order is
 * ({@link FuturesOrderService}): MARKET at once, or LIMIT waiting at its price. A share of less than the whole is
 * rounded down to the pair's step. Close All closes every open position by MARKET.
 *
 * <p>Rule: TR-17; D-127.
 * <p>Reference: Binance. <i>USDⓈ-M Futures API: Modify Isolated Position Margin, Get Position Margin Change
 * History</i>, Binance Developer Center.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FuturesPositionServiceImpl implements FuturesPositionService {

    private static final String MODIFIED = "Successfully modify position margin.";

    private static final String USER_ADJUST = "USER_ADJUST";

    private static final BigDecimal WHOLE = BigDecimal.valueOf(100);

    private static final int DEFAULT_LIMIT = 500;

    private static final int MAX_LIMIT = 1000;

    private final PaperAccountRepository accounts;
    private final FuturesPositionRepository positions;
    private final FuturesMarginAdjustmentRepository adjustments;
    private final FuturesMargins margins;
    private final FuturesOrderService orders;
    private final UserLock lock;
    private final MarketApi market;
    private final EntitlementApi entitlements;

    @Override
    @Transactional
    public FuturesPositionMarginResponse changeMargin(UUID userId, FuturesPositionMarginRequest request) {
        requireFutures(userId);
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        FuturesAccountConfig account = margins.accountConfig(userId);
        if (account.liquidating()) {
            throw new FuturesRejection(ErrorCode.PAPER_USER_IN_LIQUIDATION, -2023, "User in liquidation mode now.");
        }
        if (request.positionSide() != null && request.positionSide() != PositionSide.BOTH) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_POSITION_SIDE,
                    -4061,
                    "Order's position side does not match user's setting.",
                    "positionSide",
                    request.positionSide());
        }
        FuturesPair pair = margins.pair(request.symbol());
        FuturesPosition position = margins.openPosition(userId, pair.pairId());
        if (position == null) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_POSITION_MARGIN, -4054, "Cannot add position margin: position is 0.");
        }
        if (position.getMarginType() != MarginType.ISOLATED) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_POSITION_MARGIN, -4049, "Add margin only support for isolated position.");
        }
        BigDecimal amount = request.amount();
        BigDecimal change;
        if (request.adding()) {
            BigDecimal free = margins.crossState(userId, null).margin().crossFreeCash();
            if (amount.compareTo(free) > 0) {
                throw new FuturesRejection(
                        ErrorCode.PAPER_INSUFFICIENT_MARGIN,
                        -2019,
                        "Margin is insufficient.",
                        amount.toPlainString(),
                        free.toPlainString());
            }
            change = amount;
        } else {
            BigDecimal mark = market.currentMarkPrice(pair.symbol())
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.MARKET_PRICE_UNAVAILABLE, "no mark price of " + pair.symbol() + " is known"));
            BigDecimal removable = FuturesMarginMath.maxRemovableMargin(
                    position.getIsolatedWalletAmount(),
                    FuturesMarginMath.unrealizedPnl(
                            position.getDirection(), position.getQuantity(), position.getEntryPrice(), mark),
                    position.getQuantity().multiply(mark),
                    position.getLeverage());
            if (amount.compareTo(removable) > 0) {
                throw new FuturesRejection(
                        ErrorCode.PAPER_POSITION_MARGIN,
                        -4051,
                        "Isolated balance insufficient.",
                        amount.toPlainString(),
                        removable.toPlainString());
            }
            change = amount.negate();
        }
        position.adjustIsolatedWallet(change);
        adjustments.save(FuturesMarginAdjustment.of(
                position,
                request.adding() ? MarginAdjustmentType.MANUAL_ADD : MarginAdjustmentType.MANUAL_REDUCE,
                change,
                null,
                null));
        margins.recordChange(account);
        return new FuturesPositionMarginResponse(
                amount, 200, MODIFIED, request.type(), position.getIsolatedWalletAmount());
    }

    @Override
    @Transactional(readOnly = true)
    public List<FuturesMarginHistoryResponse> marginHistory(
            UUID userId, String symbol, Integer type, Instant startTime, Instant endTime, Integer limit) {
        requireFutures(userId);
        TimeBounds.requireOrdered(startTime, endTime);
        FuturesPair pair = margins.pair(symbol);
        Set<MarginAdjustmentType> types = type == null
                ? EnumSet.of(MarginAdjustmentType.MANUAL_ADD, MarginAdjustmentType.MANUAL_REDUCE)
                : switch (type) {
                    case 1 -> EnumSet.of(MarginAdjustmentType.MANUAL_ADD);
                    case 2 -> EnumSet.of(MarginAdjustmentType.MANUAL_REDUCE);
                    default -> throw new FieldValidationException("type must be 1 or 2", Map.of("type", "MSG15"));
                };
        String asset = margins.usdt().symbol();
        return adjustments
                .search(
                        userId,
                        pair.pairId(),
                        types,
                        TimeBounds.fromOrEarliest(startTime),
                        TimeBounds.toOrLatest(endTime),
                        PageRequest.of(0, limitOf(limit)))
                .stream()
                .map(adjustment -> new FuturesMarginHistoryResponse(
                        pair.symbol(),
                        adjustment.getAdjustmentType() == MarginAdjustmentType.MANUAL_ADD ? 1 : 2,
                        USER_ADJUST,
                        adjustment.getAmount().abs(),
                        asset,
                        PositionSide.BOTH,
                        adjustment.getIwAfterAmount(),
                        adjustment.getCreatedAt()))
                .toList();
    }

    @Override
    @Transactional
    public List<FuturesOrderResponse> close(UUID userId, FuturesClosePositionRequest request) {
        requireFutures(userId);
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        requireShape(request);
        if (request.symbol() == null) {
            return closeAll(userId);
        }
        FuturesPair pair = margins.pair(request.symbol());
        FuturesPosition position = margins.openPosition(userId, pair.pairId());
        if (position == null) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_REDUCE_ONLY_REJECTED, -2022, "ReduceOnly Order is rejected.", "symbol");
        }
        BigDecimal percent = request.percent() == null ? WHOLE : request.percent();
        BigDecimal quantity = percent.compareTo(WHOLE) == 0
                ? position.getQuantity()
                : pair.filters()
                        .floorQuantity(position.getQuantity().multiply(percent).divide(WHOLE));
        if (quantity.signum() == 0) {
            throw new FieldValidationException(
                    percent.toPlainString() + " % of the position is less than one step", Map.of("percent", "MSG15"));
        }
        return List.of(
                orders.place(userId, closing(pair.symbol(), position, request.type(), request.price(), quantity)));
    }

    /** Closes every open position by MARKET, the oldest first. */
    private List<FuturesOrderResponse> closeAll(UUID userId) {
        List<FuturesPosition> open = positions.findByUserIdAndPositionStatus(userId, PositionStatus.OPEN);
        Map<UUID, String> symbols = new LinkedHashMap<>();
        for (PairListing pair : market.pairListings(
                open.stream().map(FuturesPosition::getPairId).toList())) {
            symbols.put(pair.pairId(), pair.symbol());
        }
        List<FuturesOrderResponse> placed = new ArrayList<>(open.size());
        open.stream()
                .sorted((a, b) -> a.getOpenedAt().compareTo(b.getOpenedAt()))
                .forEach(position -> placed.add(orders.place(
                        userId,
                        closing(
                                symbols.get(position.getPairId()),
                                position,
                                OrderType.MARKET,
                                null,
                                position.getQuantity()))));
        return placed;
    }

    /** The reduce-only order on the other side of the position. */
    private static FuturesOrderRequest closing(
            String symbol, FuturesPosition position, OrderType type, BigDecimal price, BigDecimal quantity) {
        OrderSide side = position.getDirection() == PositionDirection.LONG ? OrderSide.SELL : OrderSide.BUY;
        return new FuturesOrderRequest(
                symbol,
                side,
                PositionSide.BOTH,
                type,
                type == OrderType.LIMIT ? TimeInForce.GTC : null,
                quantity,
                price,
                true,
                null,
                null,
                null,
                null,
                null);
    }

    private static void requireShape(FuturesClosePositionRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.type() != OrderType.MARKET && request.type() != OrderType.LIMIT) {
            errors.put("type", "MSG15");
        }
        if (request.type() == OrderType.LIMIT && request.price() == null) {
            errors.put("price", "MSG01");
        }
        if (request.type() == OrderType.LIMIT && request.symbol() == null) {
            errors.put("symbol", "MSG01");
        }
        if (request.type() == OrderType.MARKET && request.price() != null) {
            errors.put("price", "MSG15");
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(
                    "a close of type " + request.type() + " does not take these fields", errors);
        }
    }

    /** The Futures feature of the Trader's plan (Q-T10) and an opened paper account (MSG41). */
    private void requireFutures(UUID userId) {
        entitlements.requireFeature(userId, Feature.FUTURES_ANALYSIS);
        accounts.findByUserId(userId).orElseThrow(() -> new ResourceNotFoundException("PaperAccount", userId));
    }

    private static int limitOf(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new FieldValidationException("limit must be 1 to " + MAX_LIMIT, Map.of("limit", "MSG15"));
        }
        return limit;
    }
}
