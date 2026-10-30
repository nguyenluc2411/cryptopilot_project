package com.cryptopilot.paper.service.impl;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.paper.calculator.FuturesMarginMath;
import com.cryptopilot.paper.dto.request.FuturesLiquidationCalcRequest;
import com.cryptopilot.paper.dto.request.FuturesMaxOpenCalcRequest;
import com.cryptopilot.paper.dto.request.FuturesOpenPriceCalcRequest;
import com.cryptopilot.paper.dto.request.FuturesPnlCalcRequest;
import com.cryptopilot.paper.dto.request.FuturesTargetCalcRequest;
import com.cryptopilot.paper.dto.response.FuturesCalculatorResponse;
import com.cryptopilot.paper.exception.FuturesRejection;
import com.cryptopilot.paper.model.OpenedLot;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.PositionDirection;
import com.cryptopilot.paper.service.FuturesCalculatorService;
import com.cryptopilot.paper.service.impl.FuturesMargins.FuturesPair;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Futures calculator (TR-25), on {@link FuturesMarginMath}: the same figures an order and a position get.
 *
 * <ul>
 *   <li>The leverage is 1 to the pair's highest ({@code -4028}).
 *   <li>PnL: {@code IM = Q × E / L}, {@code PnL = side × Q × (X − E)}, {@code ROE = PnL / IM} (plan 4.2).
 *   <li>Target price: {@code E × (1 + side × ROE / L)}, rounded to the tick half up.
 *   <li>Liquidation price: the position alone in its margin, the other cross positions none (plan 4.5); a position
 *       past the largest notional of the leverage is refused ({@code -2027}), as an order of it would be.
 *   <li>Max open: as the order form's Max (plan 4.8) for a LIMIT order at the entry price with no position, the last
 *       and mark price taken as the entry, so that a short is costed at the entry plus the 0.15 % buffer.
 *   <li>Open price: the quantity-weighted mean of the parts.
 * </ul>
 *
 * <p>Rule: TR-25; D-127.
 * <p>Reference: Binance. <i>How to Use Binance Futures Calculator</i>, Binance Support.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FuturesCalculatorServiceImpl implements FuturesCalculatorService {

    private final FuturesMargins margins;
    private final EntitlementApi entitlements;

    @Override
    @Transactional(readOnly = true)
    public FuturesCalculatorResponse pnl(UUID userId, FuturesPnlCalcRequest request) {
        FuturesPair pair = pairOf(userId, request.symbol());
        int leverage = requireLeverage(pair, request.leverage());
        BigDecimal initialMargin =
                FuturesMarginMath.initialMargin(request.quantity().multiply(request.entryPrice()), leverage);
        BigDecimal pnl = FuturesMarginMath.unrealizedPnl(
                request.side(), request.quantity(), request.entryPrice(), request.exitPrice());
        return new FuturesCalculatorResponse(
                initialMargin,
                pnl,
                FuturesMarginMath.roePercent(pnl, initialMargin),
                null,
                null,
                null,
                null,
                null,
                FuturesMarginMath.maxNotionalAtLeverage(pair.tiers(), leverage),
                null,
                null);
    }

    @Override
    @Transactional(readOnly = true)
    public FuturesCalculatorResponse target(UUID userId, FuturesTargetCalcRequest request) {
        FuturesPair pair = pairOf(userId, request.symbol());
        int leverage = requireLeverage(pair, request.leverage());
        BigDecimal target =
                FuturesMarginMath.targetPrice(request.side(), request.entryPrice(), leverage, request.roe());
        return new FuturesCalculatorResponse(
                null,
                null,
                null,
                Rounding.toTickSize(target, pair.filters().tickSize(), RoundingMode.HALF_UP),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @Override
    @Transactional(readOnly = true)
    public FuturesCalculatorResponse liquidation(UUID userId, FuturesLiquidationCalcRequest request) {
        FuturesPair pair = pairOf(userId, request.symbol());
        int leverage = requireLeverage(pair, request.leverage());
        if (request.marginType() == MarginType.CROSSED && request.walletBalance() == null) {
            throw new FieldValidationException(
                    "a cross position needs the wallet balance", Map.of("walletBalance", "MSG01"));
        }
        BigDecimal notional = request.quantity().multiply(request.entryPrice());
        BigDecimal maxNotional = FuturesMarginMath.maxNotionalAtLeverage(pair.tiers(), leverage);
        if (notional.compareTo(maxNotional) > 0) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_MAX_POSITION_AT_LEVERAGE,
                    -2027,
                    "Exceeded the maximum allowable position at current leverage.");
        }
        BigDecimal initialMargin = FuturesMarginMath.initialMargin(notional, leverage);
        BigDecimal wallet = request.walletBalance() != null ? request.walletBalance() : initialMargin;
        BigDecimal liquidationPrice = FuturesMarginMath.liquidationPrice(
                request.side(),
                request.quantity(),
                request.entryPrice(),
                wallet,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                pair.tiers(),
                pair.filters().tickSize());
        return new FuturesCalculatorResponse(
                initialMargin, null, null, null, liquidationPrice, wallet, null, null, maxNotional, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public FuturesCalculatorResponse maxOpen(UUID userId, FuturesMaxOpenCalcRequest request) {
        FuturesPair pair = pairOf(userId, request.symbol());
        int leverage = requireLeverage(pair, request.leverage());
        BigDecimal maxNotional = FuturesMarginMath.maxNotionalAtLeverage(pair.tiers(), leverage);
        BigDecimal price = request.entryPrice();
        BigDecimal quantity = FuturesMarginMath.maxOpenQuantity(
                request.side() == PositionDirection.LONG ? OrderSide.BUY : OrderSide.SELL,
                price,
                price,
                price,
                leverage,
                request.walletBalance(),
                BigDecimal.ZERO,
                maxNotional,
                pair.filters().stepSize());
        return new FuturesCalculatorResponse(
                null,
                null,
                null,
                null,
                null,
                null,
                quantity,
                Rounding.toAmount(quantity.multiply(price)),
                maxNotional,
                null,
                null);
    }

    @Override
    @Transactional(readOnly = true)
    public FuturesCalculatorResponse openPrice(UUID userId, FuturesOpenPriceCalcRequest request) {
        pairOf(userId, request.symbol());
        BigDecimal openPrice = FuturesMarginMath.averageOpenPrice(request.lots().stream()
                .map(lot -> new OpenedLot(lot.price(), lot.quantity()))
                .toList());
        BigDecimal quantity = request.lots().stream()
                .map(FuturesOpenPriceCalcRequest.Lot::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new FuturesCalculatorResponse(null, null, null, null, null, null, null, null, null, openPrice, quantity);
    }

    /** The Futures feature of the Trader's plan (Q-T10) and a pair traded on Futures with brackets. */
    private FuturesPair pairOf(UUID userId, String symbol) {
        entitlements.requireFeature(userId, Feature.FUTURES_ANALYSIS);
        return margins.pair(symbol);
    }

    private static int requireLeverage(FuturesPair pair, int leverage) {
        if (leverage > FuturesMarginMath.maxLeverage(pair.tiers())) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_LEVERAGE, -4028, "Leverage " + leverage + " is not valid", "leverage", leverage);
        }
        return leverage;
    }
}
