package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.SettledFunding;
import com.cryptopilot.paper.calculator.FuturesMarginMath;
import com.cryptopilot.paper.entity.FuturesMarginAdjustment;
import com.cryptopilot.paper.entity.FuturesPosition;
import com.cryptopilot.paper.entity.PaperLedgerEntry;
import com.cryptopilot.paper.entity.PaperLedgerEntry.Ref;
import com.cryptopilot.paper.event.FuturesAccountChanged;
import com.cryptopilot.paper.model.IsolatedCashFlow;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.enums.LedgerEntryType;
import com.cryptopilot.paper.model.enums.LedgerRefType;
import com.cryptopilot.paper.model.enums.MarginAdjustmentRefType;
import com.cryptopilot.paper.model.enums.MarginAdjustmentType;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.PositionDirection;
import com.cryptopilot.paper.model.enums.PositionStatus;
import com.cryptopilot.paper.repository.FuturesMarginAdjustmentRepository;
import com.cryptopilot.paper.repository.FuturesPositionRepository;
import com.cryptopilot.paper.repository.PaperAccountRepository;
import com.cryptopilot.paper.repository.PaperFillRepository;
import com.cryptopilot.paper.repository.PaperLedgerEntryRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles the funding of paper Futures positions from the settlements the market stores (TR-24, plan 9.4):
 *
 * <ul>
 *   <li>A position owes a settlement at {@code t} when it was open then: opened before {@code t}, not closed before
 *       it, as the plan calculator's {@code entry < t ≤ exit}. Its size at {@code t} is what its fills before
 *       {@code t} opened less what they closed, so a run on time and a late one charge the same.
 *   <li>The fee is {@code −side × Q × mark × rate} at the settlement's mark: a positive rate makes longs pay and shorts
 *       receive. A fee that rounds to zero is not written.
 *   <li>It is one {@code FUNDING_FEE} ledger entry named after the position and the settlement, which a unique index
 *       keeps from being written twice: running again, or on two instances, charges nothing twice.
 *   <li>An open isolated position takes the fee in its isolated wallet by rule (B) of plan 4.3: received or paid, a
 *       payment beyond the wallet being an insurance cover; the matching engine reads its liquidation level again. A
 *       cross position, or one closed since, takes it in the cross wallet.
 * </ul>
 *
 * <p>Rule: TR-24; BR-11; D-127.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FuturesFunding {

    private final FuturesPositionRepository positions;
    private final PaperFillRepository fills;
    private final PaperLedgerEntryRepository ledger;
    private final FuturesMarginAdjustmentRepository adjustments;
    private final PaperAccountRepository accounts;
    private final PaperWallets wallets;
    private final FuturesMargins margins;
    private final PaperUserFeed feed;
    private final UserLock lock;
    private final MarketApi market;
    private final ApplicationEventPublisher events;

    /**
     * What a run settles: the settlements stored since {@code from} of every pair with a position open then or now,
     * paired with each position open at the settlement.
     */
    @Transactional(readOnly = true)
    public List<Owed> owed(Instant from, Instant now) {
        Map<UUID, FuturesPosition> candidates = new LinkedHashMap<>();
        positions.findByPositionStatus(PositionStatus.OPEN).forEach(p -> candidates.put(p.getId(), p));
        positions.findByClosedAtGreaterThanEqual(from).forEach(p -> candidates.putIfAbsent(p.getId(), p));
        Map<UUID, List<FuturesPosition>> byPair = new LinkedHashMap<>();
        candidates.values().forEach(p -> byPair.computeIfAbsent(p.getPairId(), ignored -> new ArrayList<>())
                .add(p));
        List<Owed> owed = new ArrayList<>();
        byPair.forEach((pairId, held) -> {
            for (SettledFunding settlement :
                    market.fundingHistory(pairId, from, now).settlements()) {
                for (FuturesPosition position : held) {
                    boolean openThen = position.getOpenedAt().isBefore(settlement.fundingTime())
                            && (position.getClosedAt() == null
                                    || !position.getClosedAt().isBefore(settlement.fundingTime()));
                    if (openThen) {
                        owed.add(new Owed(position.getId(), position.getUserId(), settlement));
                    }
                }
            }
        });
        return owed;
    }

    /**
     * Settles one position's funding at one settlement under its Trader's lock, unless it already was.
     *
     * @return whether a fee was written
     */
    @Transactional
    public boolean settle(Owed owed) {
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, owed.userId());
        UUID reference = referenceOf(owed);
        if (ledger.existsByRefTypeAndRefIdAndEntryType(LedgerRefType.FUNDING, reference, LedgerEntryType.FUNDING_FEE)) {
            return false;
        }
        FuturesPosition position = positions
                .findById(owed.positionId())
                .orElseThrow(() -> new IllegalStateException("position " + owed.positionId() + " is not stored"));
        SettledFunding settlement = owed.settlement();
        OrderSide opening = position.getDirection() == PositionDirection.LONG ? OrderSide.BUY : OrderSide.SELL;
        BigDecimal size = fills.sizeBefore(position.getId(), opening, settlement.fundingTime());
        if (size.signum() <= 0) {
            return false;
        }
        BigDecimal fee = FuturesMarginMath.fundingFee(
                position.getDirection(), size, settlement.markPrice(), settlement.fundingRate());
        if (fee.signum() == 0) {
            return false;
        }
        PaperAccount account = accounts.findByUserId(owed.userId())
                .orElseThrow(() -> new IllegalStateException("account " + owed.userId() + " is not stored"));
        CoinListing usdt = margins.usdt();
        wallets.changeFutures(
                account, usdt, fee, LedgerEntryType.FUNDING_FEE, Ref.to(LedgerRefType.FUNDING, reference));
        position.addFunding(fee);
        if (position.isOpen() && position.getMarginType() == MarginType.ISOLATED) {
            IsolatedCashFlow flow = FuturesMarginMath.fundingFlow(position.getIsolatedWalletAmount(), fee);
            PaperLedgerEntry cover = null;
            if (flow.insuranceCover().signum() > 0) {
                cover = wallets.changeFutures(
                        account,
                        usdt,
                        flow.insuranceCover(),
                        LedgerEntryType.INSURANCE_COVER,
                        Ref.to(LedgerRefType.POSITION, position.getId()));
            }
            if (flow.isolatedWalletChange().signum() != 0) {
                position.adjustIsolatedWallet(flow.isolatedWalletChange());
                adjustments.save(
                        cover == null
                                ? FuturesMarginAdjustment.of(
                                        position,
                                        MarginAdjustmentType.FUNDING,
                                        flow.isolatedWalletChange(),
                                        MarginAdjustmentRefType.FUNDING,
                                        reference)
                                : FuturesMarginAdjustment.of(
                                        position,
                                        MarginAdjustmentType.FUNDING,
                                        flow.isolatedWalletChange(),
                                        MarginAdjustmentRefType.LEDGER,
                                        cover.getId()));
            }
        }
        margins.recordChange(margins.accountConfig(owed.userId()));
        feed.futuresAccount(account);
        events.publishEvent(new FuturesAccountChanged(owed.userId(), position.getPairId()));
        return true;
    }

    /** The id a funding fee is written under: one per position and settlement instant. */
    static UUID referenceOf(Owed owed) {
        return UUID.nameUUIDFromBytes(
                (owed.positionId() + "@" + owed.settlement().fundingTime().toEpochMilli())
                        .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * One settlement a position owes.
     *
     * @param positionId the position
     * @param userId its Trader
     * @param settlement the settlement, with its rate and mark
     */
    public record Owed(UUID positionId, UUID userId, SettledFunding settlement) {}
}
