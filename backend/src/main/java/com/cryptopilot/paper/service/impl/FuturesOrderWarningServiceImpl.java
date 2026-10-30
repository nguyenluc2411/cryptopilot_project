package com.cryptopilot.paper.service.impl;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.calculator.FuturesMarginMath;
import com.cryptopilot.paper.calculator.FuturesOrderWarnings;
import com.cryptopilot.paper.dto.request.FuturesOrderRequest;
import com.cryptopilot.paper.dto.response.FuturesOrderWarningsResponse;
import com.cryptopilot.paper.entity.FuturesPosition;
import com.cryptopilot.paper.entity.FuturesSymbolConfig;
import com.cryptopilot.paper.model.FuturesAccountMargin;
import com.cryptopilot.paper.model.FuturesCrossState;
import com.cryptopilot.paper.model.OpenedLot;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.PositionDirection;
import com.cryptopilot.paper.model.enums.PositionSide;
import com.cryptopilot.paper.model.enums.PositionStatus;
import com.cryptopilot.paper.repository.FuturesPositionRepository;
import com.cryptopilot.paper.repository.PaperAccountRepository;
import com.cryptopilot.paper.service.FuturesOrderWarningService;
import com.cryptopilot.paper.service.impl.FuturesMargins.FuturesPair;
import com.cryptopilot.user.RiskProfileApi;
import com.cryptopilot.user.RiskProfileParameters;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The warnings of the Futures order form (TR-26) on {@link FuturesOrderWarnings}. The order is shaped as placement
 * takes it; only the part that opens or increases the position is judged, an order that only reduces raising none.
 *
 * <ul>
 *   <li>The cost and the balance it is paid from are placement's (4.6, 4.7), at the current last and mark price.
 *   <li>The liquidation price is estimated for the position once the order filled at its limit price, or at the mark
 *       for a MARKET order: the entry the size-weighted mean with what is held on that side. Isolated, the wallet is
 *       the one held plus what the fill would move into it (4.3); cross, the cross wallet with the maintenance margin
 *       and the unrealized profit of the other cross positions (4.5). A hedge position is estimated alone, without
 *       the other side it may share a price with.
 *   <li>The thresholds are the trading plan's system settings and the Trader's risk profile.
 * </ul>
 *
 * <p>Rule: TR-26; BR-28, BR-29, BR-66; D-127.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FuturesOrderWarningServiceImpl implements FuturesOrderWarningService {

    static final String LOW_RR = "WARN_LOW_RR_RATIO";
    static final String WIDE_STOP_LOSS = "WARN_WIDE_STOP_LOSS_PERCENT";
    static final String HIGH_FUNDING_RATE = "WARN_HIGH_FUNDING_RATE";

    private final PaperAccountRepository accounts;
    private final FuturesPositionRepository positions;
    private final FuturesMargins margins;
    private final MarketApi market;
    private final EntitlementApi entitlements;
    private final RiskProfileApi riskProfiles;
    private final SystemSettingApi settings;

    @Override
    @Transactional(readOnly = true)
    public FuturesOrderWarningsResponse warnings(UUID userId, FuturesOrderRequest request) {
        entitlements.requireFeature(userId, Feature.FUTURES_ANALYSIS);
        accounts.findByUserId(userId).orElseThrow(() -> new ResourceNotFoundException("PaperAccount", userId));
        FuturesOrderServiceImpl.requireShape(request);
        PositionSide positionSide = FuturesMargins.positionSide(
                margins.accountConfig(userId), request.positionSide(), request.reduceOnly() != null);
        FuturesPair pair = margins.pair(request.symbol());
        FuturesSymbolConfig config = margins.symbolConfig(userId, pair.pairId(), pair.tiers());
        int leverage = config.getLeverage();
        boolean cross = config.getMarginType() == MarginType.CROSSED;
        BigDecimal mark = market.currentMarkPrice(pair.symbol())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.MARKET_PRICE_UNAVAILABLE, "no mark price of " + pair.symbol() + " is known"));
        BigDecimal last =
                market.currentLastPrice(MarketType.FUTURES, pair.symbol()).orElse(mark);
        FuturesCrossState state = margins.crossState(userId, null);
        FuturesAccountMargin margin = state.margin();
        BigDecimal budget = cross ? margin.availableBalance() : margin.crossFreeCash();
        FuturesPosition position = margins.openPosition(userId, pair.pairId(), positionSide);
        BigDecimal size = position == null ? BigDecimal.ZERO : position.signedQuantity();
        boolean reducing = request.reducing() || FuturesOrderServiceImpl.closesHedgeSide(positionSide, request.side());
        BigDecimal opening =
                reducing ? BigDecimal.ZERO : FuturesOrderServiceImpl.opening(request.side(), request.quantity(), size);
        if (opening.signum() == 0) {
            return new FuturesOrderWarningsResponse(BigDecimal.ZERO, budget, BigDecimal.ZERO, List.of());
        }
        PositionDirection direction = PositionDirection.openedBy(request.side());
        BigDecimal entry = request.price() != null ? request.price() : mark;
        BigDecimal cost = FuturesMarginMath.orderCost(request.side(), opening, request.price(), last, mark, leverage);
        boolean adds = size.signum() == direction.sign().signum();
        BigDecimal held = adds ? size.abs() : BigDecimal.ZERO;
        BigDecimal quantityAfter = held.add(opening);
        BigDecimal entryAfter = adds
                ? FuturesMarginMath.averageOpenPrice(
                        List.of(new OpenedLot(position.getEntryPrice(), held), new OpenedLot(entry, opening)))
                : entry;
        BigDecimal liquidation = cross
                ? crossLiquidation(userId, pair, positionSide, direction, quantityAfter, entryAfter, state)
                : FuturesMarginMath.liquidationPrice(
                        direction,
                        quantityAfter,
                        entryAfter,
                        (adds ? position.getIsolatedWalletAmount() : BigDecimal.ZERO)
                                .add(FuturesMarginMath.isolatedFillMargin(direction, opening, entry, mark, leverage)),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        pair.tiers(),
                        pair.filters().tickSize());
        RiskProfileParameters profile = riskProfiles.parametersOf(userId);
        return new FuturesOrderWarningsResponse(
                cost,
                budget,
                liquidation,
                FuturesOrderWarnings.evaluate(new FuturesOrderWarnings.Input(
                        direction,
                        entry,
                        opening,
                        leverage,
                        cost,
                        budget,
                        state.walletBalance(),
                        liquidation,
                        request.stopLossPrice(),
                        request.takeProfitPrice(),
                        market.currentFundingRate(pair.symbol()).orElse(null),
                        profile.maxFuturesLeverage(),
                        profile.riskPerTradePercent(),
                        settings.decimal(LOW_RR),
                        settings.decimal(WIDE_STOP_LOSS),
                        settings.decimal(HIGH_FUNDING_RATE))));
    }

    /**
     * The liquidation price of a cross position once the order filled: the cross wallet, with the maintenance margin
     * and the unrealized profit of every other cross position at its mark (4.5).
     */
    private BigDecimal crossLiquidation(
            UUID userId,
            FuturesPair pair,
            PositionSide positionSide,
            PositionDirection direction,
            BigDecimal quantity,
            BigDecimal entry,
            FuturesCrossState state) {
        List<FuturesPosition> others = positions.findByUserIdAndPositionStatus(userId, PositionStatus.OPEN).stream()
                .filter(other -> other.getMarginType() == MarginType.CROSSED)
                .filter(other -> !(other.getPairId().equals(pair.pairId()) && other.getPositionSide() == positionSide))
                .toList();
        Map<UUID, BigDecimal> marks = margins.marks(others);
        BigDecimal maintenance = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        for (FuturesPosition other : others) {
            BigDecimal mark = marks.get(other.getPairId());
            maintenance = maintenance.add(maintenance(
                    market.leverageBrackets(other.getPairId()),
                    other.getQuantity().multiply(mark)));
            unrealized = unrealized.add(FuturesMarginMath.unrealizedPnl(
                    other.getDirection(), other.getQuantity(), other.getEntryPrice(), mark));
        }
        return FuturesMarginMath.liquidationPrice(
                direction,
                quantity,
                entry,
                state.crossWalletBalance(),
                maintenance,
                unrealized,
                pair.tiers(),
                pair.filters().tickSize());
    }

    /** The maintenance margin by the notional's bracket, the largest bracket's above every cap. */
    private static BigDecimal maintenance(List<LeverageTier> tiers, BigDecimal notional) {
        List<LeverageTier> resolved = FuturesMarginMath.withMaintenanceAmounts(tiers);
        LeverageTier tier = FuturesMarginMath.bracketFor(resolved, notional).orElse(resolved.getLast());
        return Rounding.toAmount(
                notional.abs().multiply(tier.maintenanceMarginRate()).subtract(tier.maintenanceAmount()));
    }
}
