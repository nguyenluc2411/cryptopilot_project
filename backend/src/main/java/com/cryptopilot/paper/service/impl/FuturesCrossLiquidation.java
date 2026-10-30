package com.cryptopilot.paper.service.impl;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.calculator.FuturesMarginMath;
import com.cryptopilot.paper.entity.FuturesAccountConfig;
import com.cryptopilot.paper.entity.FuturesPosition;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.event.FuturesAccountChanged;
import com.cryptopilot.paper.event.FuturesRiskNotice;
import com.cryptopilot.paper.event.PaperOrderCanceled;
import com.cryptopilot.paper.matching.BookSweep;
import com.cryptopilot.paper.model.CrossAccount;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.PositionStatus;
import com.cryptopilot.paper.repository.FuturesPositionRepository;
import com.cryptopilot.paper.repository.PaperOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The liquidation of a Trader's cross Futures positions (TR-23, plan 4.12 and 9.3), one step at a time under the
 * Trader's lock, decided on the database at the latest marks:
 *
 * <ol>
 *   <li>The cross margin balance, cross wallet plus the cross positions' unrealized profit, is compared with their
 *       maintenance margin. Above it nothing is due, and a liquidation still marked as running ends.
 *   <li>At or below it, the first step marks the account as liquidating ({@code liquidating_since}, which refuses
 *       orders and changes with {@code -2023}) and cancels every open order of the account ({@code LIQUIDATION}).
 *   <li>The position closed first is the one losing most, preferring a pair whose mark is current. A step acting for
 *       its pair closes it at that pair's mark with a system MARKET order ({@code autoclose-…}); for a position on
 *       another pair the step names that pair, whose own turn in the engine closes it, never this transaction.
 *   <li>After a close the account is judged again: back above the maintenance margin, or with no cross position left,
 *       the liquidation ends in the same transaction; otherwise the step names the pair to close next.
 * </ol>
 *
 * <p>A pair without any mark price cannot be valued: the step does nothing and the liquidation, if it runs, waits for
 * the safety job to try again.
 *
 * <p>Rule: TR-23; D-97, D-127.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class FuturesCrossLiquidation {

    private static final Logger log = LoggerFactory.getLogger(FuturesCrossLiquidation.class);

    private final FuturesPositionRepository positions;
    private final PaperOrderRepository orders;
    private final FuturesMargins margins;
    private final FuturesSettlement settlement;
    private final FuturesArrival arrival;
    private final FuturesOrderLists tpSl;
    private final PaperUserFeed feed;
    private final MarketApi market;
    private final SystemSettingApi settings;
    private final ApplicationEventPublisher events;

    /**
     * One step of the Trader's cross liquidation; see the class comment.
     *
     * @param pairId the pair the step acts for, whose position it may close; {@code null} to only name the pair whose
     *     position closes first
     * @return the pair whose cross position is to close next, or empty when nothing more is due
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<UUID> step(PaperAccount account, UUID pairId, Instant at) {
        UUID userId = account.userId();
        FuturesAccountConfig config = margins.accountConfig(userId);
        Optional<Judgement> judged = judge(userId);
        if (judged.isEmpty()) {
            return Optional.empty();
        }
        Judgement judgement = judged.get();
        if (!judgement.due()) {
            end(config);
            return Optional.empty();
        }
        if (!config.liquidating()) {
            config.startLiquidation(at);
            margins.recordChange(config);
            cancelOrders(account, at);
            log.info(
                    "TR-23 cross liquidation of user {} started: margin balance {} at or below maintenance {}",
                    userId,
                    judgement.marginBalance(),
                    judgement.maintenance());
        }
        FuturesPosition first = judgement.firstToClose();
        if (pairId == null || !first.getPairId().equals(pairId)) {
            return Optional.of(first.getPairId());
        }
        close(account, first, judgement, at);
        Optional<Judgement> after = judge(userId);
        if (after.isEmpty()) {
            // Left to the safety job, which judges the account again once every mark is known.
            return Optional.empty();
        }
        if (!after.get().due()) {
            end(config);
            log.info("TR-23 cross liquidation of user {} ended", userId);
            return Optional.empty();
        }
        return Optional.of(after.get().firstToClose().getPairId());
    }

    /** The cross side of every Trader with an open cross position, which the engine screens from (TR-23). */
    @Transactional(propagation = Propagation.MANDATORY)
    List<CrossAccount> accounts() {
        return positions.findByPositionStatusAndMarginType(PositionStatus.OPEN, MarginType.CROSSED).stream()
                .map(FuturesPosition::getUserId)
                .distinct()
                .map(this::account)
                .flatMap(Optional::stream)
                .toList();
    }

    /** The cross side of the Trader's account as it is now, or empty without an open cross position. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<CrossAccount> account(UUID userId) {
        List<FuturesPosition> open = positions.findByUserIdAndPositionStatus(userId, PositionStatus.OPEN);
        List<CrossAccount.Leg> legs = open.stream()
                .filter(position -> position.getMarginType() == MarginType.CROSSED)
                .map(position -> new CrossAccount.Leg(
                        position.getPairId(),
                        position.getDirection(),
                        position.getQuantity(),
                        position.getEntryPrice(),
                        market.leverageBrackets(position.getPairId())))
                .toList();
        if (legs.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CrossAccount(userId, margins.crossWallet(userId, open), legs));
    }

    /**
     * The cross margin balance and maintenance margin of the Trader at the latest marks (TR-43); empty without cross
     * positions or while a pair of them has no mark.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<CrossMargin> crossMargin(UUID userId) {
        return judge(userId)
                .filter(judgement -> judgement.firstToClose() != null)
                .map(judgement -> new CrossMargin(judgement.marginBalance(), judgement.maintenance()));
    }

    /** The cross margin balance, cross wallet plus cross unrealized profit, and the cross maintenance margin. */
    record CrossMargin(BigDecimal marginBalance, BigDecimal maintenance) {}

    /** The Trader's cross positions at their latest marks; empty when one of them has no mark at all. */
    private Optional<Judgement> judge(UUID userId) {
        List<FuturesPosition> open = positions.findByUserIdAndPositionStatus(userId, PositionStatus.OPEN);
        List<FuturesPosition> cross = open.stream()
                .filter(position -> position.getMarginType() == MarginType.CROSSED)
                .toList();
        if (cross.isEmpty()) {
            return Optional.of(Judgement.none());
        }
        Map<UUID, BigDecimal> marks = new HashMap<>();
        Set<UUID> current = new LinkedHashSet<>();
        for (PairListing pair : market.pairListings(
                cross.stream().map(FuturesPosition::getPairId).collect(Collectors.toSet()))) {
            Optional<BigDecimal> mark = market.currentMarkPrice(pair.symbol());
            if (mark.isEmpty()) {
                log.warn("TR-23 cross positions of user {} not judged: no mark price of {}", userId, pair.symbol());
                return Optional.empty();
            }
            marks.put(pair.pairId(), mark.get());
            if (margins.freshMark(pair.symbol()).isPresent()) {
                current.add(pair.pairId());
            }
        }
        BigDecimal crossWallet =
                margins.crossState(userId, open, marks, BigDecimal.ZERO).crossWalletBalance();
        BigDecimal unrealized = BigDecimal.ZERO;
        BigDecimal maintenance = BigDecimal.ZERO;
        Map<UUID, BigDecimal> unrealizedByPosition = new HashMap<>();
        for (FuturesPosition position : cross) {
            BigDecimal mark = marks.get(position.getPairId());
            BigDecimal pnl = FuturesMarginMath.unrealizedPnl(
                    position.getDirection(), position.getQuantity(), position.getEntryPrice(), mark);
            unrealizedByPosition.put(position.getId(), pnl);
            unrealized = unrealized.add(pnl);
            maintenance = maintenance.add(FuturesLiquidation.maintenance(
                    market.leverageBrackets(position.getPairId()),
                    position.getQuantity().multiply(mark)));
        }
        // The position losing most goes first; a pair whose mark is current before a stale one (plan 9.3).
        FuturesPosition first = cross.stream()
                .min(Comparator.<FuturesPosition, Boolean>comparing(position -> !current.contains(position.getPairId()))
                        .thenComparing(position -> unrealizedByPosition.get(position.getId()))
                        .thenComparing(FuturesPosition::getOpenedAt))
                .orElseThrow();
        return Optional.of(new Judgement(
                crossWallet.add(unrealized), maintenance, first, marks, unrealized, unrealizedByPosition));
    }

    /**
     * Closes all of the position with the system's MARKET order: at its pair's mark, or, on a pair whose mark is stale,
     * at the average price of the book it takes (plan 9.3), the stale mark being no price anyone could trade at.
     */
    private void close(PaperAccount account, FuturesPosition position, Judgement judgement, Instant at) {
        OrderSide side = position.getDirection().closingSide();
        BigDecimal mark = closingPrice(position, side, judgement.marks().get(position.getPairId()));
        PaperOrder order = orders.save(PaperOrder.futuresMarket(
                account.userId(),
                position.getPairId(),
                FuturesLiquidation.closingKey(FuturesLiquidation.AUTOCLOSE, at, position.getId()),
                side,
                position.getPositionSide(),
                position.getQuantity(),
                true));
        BigDecimal others =
                judgement.unrealized().subtract(judgement.unrealizedByPosition().get(position.getId()));
        settlement.liquidateCross(
                account, order, position, mark, settings.decimal(FuturesLiquidation.CLEARANCE_FEE), others, at);
        events.publishEvent(new FuturesRiskNotice(
                account.userId(),
                FuturesRiskNotice.Kind.LIQUIDATION,
                market.tradablePair(position.getPairId(), MarketType.FUTURES)
                        .map(TradablePair::symbol)
                        .orElse(null),
                MarginType.CROSSED.name(),
                position.getId(),
                mark,
                null,
                at));
        log.info("TR-23 cross position {} of user {} liquidated at mark {}", position.getId(), account.userId(), mark);
    }

    /**
     * The price a liquidation closes the position at: its pair's mark while current; on a stale pair the average price
     * of the book for the whole size, the rest past the book at its last level, as a MARKET order fills. Without a book
     * either, the last mark known is all there is.
     */
    private BigDecimal closingPrice(FuturesPosition position, OrderSide side, BigDecimal mark) {
        Optional<String> symbol = market.pairListings(List.of(position.getPairId())).stream()
                .findFirst()
                .map(PairListing::symbol);
        if (symbol.isEmpty() || margins.freshMark(symbol.get()).isPresent()) {
            return mark;
        }
        BookSweep.Result sweep = FuturesArrival.sweep(
                arrival.bookFor(position.getPairId(), symbol.get(), side), side, position.getQuantity(), null);
        if (sweep.isEmpty()) {
            log.warn(
                    "TR-23 stale pair {} has no book either: position {} closes at the last mark",
                    symbol.get(),
                    position.getId());
            return mark;
        }
        return Rounding.divide(sweep.notional(), sweep.quantity());
    }

    /** Cancels every open order of the account, on every pair, conditional ones included (plan 4.12 step 2). */
    private void cancelOrders(PaperAccount account, Instant at) {
        Set<UUID> pairs = new LinkedHashSet<>();
        List<PaperOrder> cancelled = orders.findByUserIdAndMarketTypeAndOrderStatusInOrderByCreatedAtDescIdDesc(
                account.userId(), MarketType.FUTURES, FuturesLiquidation.CANCELLED_ON_LIQUIDATION);
        for (PaperOrder order : cancelled) {
            order.cancel(FuturesLiquidation.LIQUIDATION, at);
            feed.futuresOrder(account, order);
            events.publishEvent(new PaperOrderCanceled(order.getId()));
            pairs.add(order.getPairId());
        }
        tpSl.endAll(cancelled);
        pairs.forEach(pair -> events.publishEvent(new FuturesAccountChanged(account.userId(), pair)));
    }

    private void end(FuturesAccountConfig config) {
        if (config.liquidating()) {
            config.endLiquidation();
            margins.recordChange(config);
        }
    }

    /**
     * The cross side of the account at the marks.
     *
     * @param firstToClose the position closed first, or {@code null} without cross positions
     */
    private record Judgement(
            BigDecimal marginBalance,
            BigDecimal maintenance,
            FuturesPosition firstToClose,
            Map<UUID, BigDecimal> marks,
            BigDecimal unrealized,
            Map<UUID, BigDecimal> unrealizedByPosition) {

        static Judgement none() {
            return new Judgement(BigDecimal.ZERO, BigDecimal.ZERO, null, Map.of(), BigDecimal.ZERO, Map.of());
        }

        /** Whether a liquidation is due: a cross position, and a margin balance at or below its maintenance. */
        boolean due() {
            return firstToClose != null && marginBalance.compareTo(maintenance) <= 0;
        }
    }
}
