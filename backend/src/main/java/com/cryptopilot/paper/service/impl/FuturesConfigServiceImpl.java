package com.cryptopilot.paper.service.impl;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.calculator.FuturesMarginMath;
import com.cryptopilot.paper.dto.request.FuturesLeverageRequest;
import com.cryptopilot.paper.dto.request.FuturesMarginTypeRequest;
import com.cryptopilot.paper.dto.response.FuturesAccountConfigResponse;
import com.cryptopilot.paper.dto.response.FuturesLeverageBracketResponse;
import com.cryptopilot.paper.dto.response.FuturesLeverageResponse;
import com.cryptopilot.paper.dto.response.FuturesPositionModeResponse;
import com.cryptopilot.paper.dto.response.FuturesSymbolConfigResponse;
import com.cryptopilot.paper.entity.FuturesAccountConfig;
import com.cryptopilot.paper.entity.FuturesPosition;
import com.cryptopilot.paper.entity.FuturesSymbolConfig;
import com.cryptopilot.paper.exception.FuturesRejection;
import com.cryptopilot.paper.model.FuturesCrossState;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.PositionStatus;
import com.cryptopilot.paper.repository.FuturesPositionRepository;
import com.cryptopilot.paper.repository.FuturesSymbolConfigRepository;
import com.cryptopilot.paper.repository.PaperAccountRepository;
import com.cryptopilot.paper.repository.PaperOrderRepository;
import com.cryptopilot.paper.service.FuturesConfigService;
import com.cryptopilot.paper.service.impl.FuturesMargins.FuturesPair;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The paper Futures settings, with the exchange's rules for changing them: a leverage within the pair's brackets, never
 * lowered on an open isolated position and lowered on a cross one only while the margin covers it, never above the
 * notional the position has; a margin type changed only on a pair without a position or an open order; nothing
 * changed while a cross liquidation runs. Changes hold the Trader's lock, as orders and transfers do, and raise the
 * Futures account version.
 *
 * <p>Rule: TR-14; D-127; Q-T10.
 * <p>Reference: Binance. <i>USDⓈ-M Futures API: Change Initial Leverage, Change Margin Type, Symbol Configuration,
 * Futures Account Configuration, Notional and Leverage Brackets</i>, Binance Developer Center.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FuturesConfigServiceImpl implements FuturesConfigService {

    /** The orders that keep a pair's margin type fixed: working, and pending legs of a group. */
    private static final Set<OrderStatus> OPEN_ORDERS =
            EnumSet.of(OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED, OrderStatus.PENDING_NEW);

    private final PaperAccountRepository accounts;
    private final FuturesSymbolConfigRepository symbolConfigs;
    private final FuturesPositionRepository positions;
    private final PaperOrderRepository orders;
    private final FuturesMargins margins;
    private final UserLock lock;
    private final MarketApi market;
    private final EntitlementApi entitlements;
    private final PaperUserFeed feed;

    @Override
    @Transactional
    public FuturesAccountConfigResponse accountConfig(UUID userId) {
        requireFutures(userId);
        FuturesAccountConfig config = margins.accountConfig(userId);
        return new FuturesAccountConfigResponse(
                0,
                !config.liquidating(),
                true,
                !config.liquidating(),
                config.isDualSidePosition(),
                false,
                config.getUpdatedAt());
    }

    @Override
    @Transactional
    public FuturesPositionModeResponse positionMode(UUID userId) {
        requireFutures(userId);
        return new FuturesPositionModeResponse(margins.accountConfig(userId).isDualSidePosition());
    }

    @Override
    @Transactional(readOnly = true)
    public List<FuturesSymbolConfigResponse> symbolConfigs(UUID userId, String symbol) {
        requireFutures(userId);
        if (symbol != null) {
            FuturesPair pair = margins.pair(symbol);
            return List.of(response(pair.symbol(), pair.tiers(), configOf(userId, pair)));
        }
        List<FuturesSymbolConfig> configured = symbolConfigs.findByUserId(userId);
        List<FuturesSymbolConfigResponse> responses = new ArrayList<>(configured.size());
        for (PairListing listing : market.pairListings(
                configured.stream().map(FuturesSymbolConfig::getPairId).toList())) {
            List<LeverageTier> tiers = market.leverageBrackets(listing.pairId());
            configured.stream()
                    .filter(c -> c.getPairId().equals(listing.pairId()))
                    .findFirst()
                    .ifPresent(c -> responses.add(response(listing.symbol(), tiers, c)));
        }
        responses.sort((a, b) -> a.symbol().compareTo(b.symbol()));
        return responses;
    }

    @Override
    @Transactional
    public FuturesLeverageResponse changeLeverage(UUID userId, FuturesLeverageRequest request) {
        PaperAccount trader = requireFutures(userId);
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        FuturesAccountConfig account = requireNotLiquidating(userId);
        FuturesPair pair = margins.pair(request.symbol());
        int leverage = request.leverage();
        int highest = FuturesMarginMath.maxLeverage(pair.tiers());
        if (leverage < 1 || leverage > highest) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_LEVERAGE, -4028, "Leverage " + leverage + " is not valid", "leverage", leverage);
        }
        FuturesSymbolConfig config = configOf(userId, pair);
        BigDecimal maxNotional = FuturesMarginMath.maxNotionalAtLeverage(pair.tiers(), leverage);
        List<FuturesPosition> open =
                positions.findByUserIdAndPairIdAndPositionStatus(userId, pair.pairId(), PositionStatus.OPEN);
        if (!open.isEmpty()) {
            requireLeverageFits(userId, open, config, leverage, maxNotional);
            open.forEach(position -> position.changeLeverage(leverage));
            margins.recordChange(account);
            feed.futuresAccount(trader);
        }
        config.changeLeverage(leverage);
        symbolConfigs.save(config);
        return new FuturesLeverageResponse(pair.symbol(), leverage, maxNotional);
    }

    @Override
    @Transactional
    public FuturesSymbolConfigResponse changeMarginType(UUID userId, FuturesMarginTypeRequest request) {
        requireFutures(userId);
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        requireNotLiquidating(userId);
        FuturesPair pair = margins.pair(request.symbol());
        FuturesSymbolConfig config = configOf(userId, pair);
        if (config.getMarginType() == request.marginType()) {
            throw new FuturesRejection(ErrorCode.PAPER_MARGIN_TYPE, -4046, "No need to change margin type.");
        }
        if (!orders.findByUserIdAndPairIdAndMarketTypeAndOrderStatusInOrderByCreatedAtAscIdAsc(
                        userId, pair.pairId(), MarketType.FUTURES, OPEN_ORDERS)
                .isEmpty()) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_MARGIN_TYPE, -4047, "Margin type cannot be changed if there exists open orders.");
        }
        if (!positions
                .findByUserIdAndPairIdAndPositionStatus(userId, pair.pairId(), PositionStatus.OPEN)
                .isEmpty()) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_MARGIN_TYPE, -4048, "Margin type cannot be changed if there exists position.");
        }
        config.changeMarginType(request.marginType());
        return response(pair.symbol(), pair.tiers(), symbolConfigs.save(config));
    }

    @Override
    @Transactional(readOnly = true)
    public FuturesLeverageBracketResponse leverageBrackets(UUID userId, String symbol) {
        requireFutures(userId);
        FuturesPair pair = margins.pair(symbol);
        List<FuturesLeverageBracketResponse.Bracket> brackets =
                FuturesMarginMath.withMaintenanceAmounts(pair.tiers()).stream()
                        .map(t -> new FuturesLeverageBracketResponse.Bracket(
                                t.bracketNo(),
                                t.maxLeverage(),
                                t.notionalCap(),
                                t.notionalFloor(),
                                t.maintenanceMarginRate(),
                                t.maintenanceAmount()))
                        .toList();
        return new FuturesLeverageBracketResponse(pair.symbol(), BigDecimal.ONE, brackets);
    }

    /**
     * The open position must stay within the maximum notional of the new leverage; a lower leverage is refused on an
     * isolated position and, on a cross one, when the cross margin would no longer cover the larger initial margin.
     */
    private void requireLeverageFits(
            UUID userId, List<FuturesPosition> open, FuturesSymbolConfig config, int leverage, BigDecimal maxNotional) {
        Map<UUID, BigDecimal> marks = margins.marks(open);
        for (FuturesPosition position : open) {
            BigDecimal notional = position.getQuantity().multiply(marks.get(position.getPairId()));
            if (notional.compareTo(maxNotional) > 0) {
                throw new FuturesRejection(
                        ErrorCode.PAPER_MAX_POSITION_AT_LEVERAGE,
                        -2027,
                        "Exceeded the maximum allowable position at current leverage.");
            }
        }
        if (leverage >= config.getLeverage()) {
            return;
        }
        if (config.getMarginType() == MarginType.ISOLATED) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_LEVERAGE_MARGIN,
                    -4161,
                    "Leverage reduction is not supported in Isolated Margin Mode with open positions.");
        }
        List<FuturesPosition> every = positions.findByUserIdAndPositionStatus(userId, PositionStatus.OPEN);
        Map<UUID, BigDecimal> everyMark = margins.marks(every);
        FuturesCrossState cross =
                margins.crossState(userId, every, everyMark, margins.openOrderMargin(userId, every, null));
        BigDecimal extra = BigDecimal.ZERO;
        for (FuturesPosition position : open) {
            BigDecimal notional = position.getQuantity().multiply(everyMark.get(position.getPairId()));
            extra = extra.add(FuturesMarginMath.initialMargin(notional, leverage)
                    .subtract(FuturesMarginMath.initialMargin(notional, position.getLeverage())));
        }
        BigDecimal left = cross.crossWalletBalance()
                .add(cross.crossUnrealizedPnl())
                .subtract(cross.crossInitialMargin())
                .subtract(cross.openOrderInitialMargin())
                .subtract(extra);
        if (left.signum() < 0) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_LEVERAGE_MARGIN,
                    -2028,
                    "Leverage is smaller than permitted: insufficient margin balance.");
        }
    }

    private FuturesAccountConfig requireNotLiquidating(UUID userId) {
        FuturesAccountConfig config = margins.accountConfig(userId);
        if (config.liquidating()) {
            throw new FuturesRejection(ErrorCode.PAPER_USER_IN_LIQUIDATION, -2023, "User in liquidation mode now.");
        }
        return config;
    }

    /** The Futures feature of the Trader's plan (Q-T10) and an opened paper account (MSG41). */
    private PaperAccount requireFutures(UUID userId) {
        entitlements.requireFeature(userId, Feature.FUTURES_ANALYSIS);
        return accounts.findByUserId(userId).orElseThrow(() -> new ResourceNotFoundException("PaperAccount", userId));
    }

    private FuturesSymbolConfig configOf(UUID userId, FuturesPair pair) {
        return margins.symbolConfig(userId, pair.pairId(), pair.tiers());
    }

    private static FuturesSymbolConfigResponse response(
            String symbol, List<LeverageTier> tiers, FuturesSymbolConfig config) {
        return new FuturesSymbolConfigResponse(
                symbol,
                config.getMarginType(),
                false,
                config.getLeverage(),
                FuturesMarginMath.maxNotionalAtLeverage(tiers, config.getLeverage()));
    }
}
