package com.cryptopilot.paper.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.paper.model.FuturesAccountMargin;
import com.cryptopilot.paper.model.IsolatedCashFlow;
import com.cryptopilot.paper.model.PositionFill;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.PositionDirection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The money invariants of the Futures wallet, checked after every step of random sequences of transfers, opening,
 * increasing, reducing and closing positions, adding and removing isolated margin, funding received and paid, mark
 * moves and liquidations, on an in-memory account driven only by {@link FuturesMarginMath}. The engine repeats these
 * checks on the database; here they fix the arithmetic first.
 *
 * <ul>
 *   <li>the ledger sums to the wallet balance;
 *   <li>every isolated wallet is zero or positive and equals the sum of its adjustments; a closed one is zero;
 *   <li>the loss of an isolated position never lowers the cross wallet;
 *   <li>funding an isolated wallet never takes the cross wallet from zero or above to below zero;
 *   <li>with no cross position left the cross wallet is not negative (after the insurance cover).
 * </ul>
 *
 * <p>Rule: TR-40; D-127.
 */
class FuturesMoneyPropertyTest {

    private static final List<LeverageTier> TIERS = List.of(
            new LeverageTier(1, BigDecimal.ZERO, new BigDecimal("50000"), 125, new BigDecimal("0.004"), null),
            new LeverageTier(2, new BigDecimal("50000"), new BigDecimal("600000"), 100, new BigDecimal("0.005"), null),
            new LeverageTier(
                    3, new BigDecimal("600000"), new BigDecimal("3000000"), 75, new BigDecimal("0.0065"), null));

    private static final BigDecimal TICK = new BigDecimal("0.01");
    private static final BigDecimal STEP = new BigDecimal("0.001");
    private static final BigDecimal LIQUIDATION_FEE_RATE = new BigDecimal("0.0125");
    private static final int PAIRS = 3;
    private static final int STEPS = 300;

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233, 377, 610, 987, 1597, 2584, 4181, 6765, 10946})
    void TR40_randomSequences_keepTheMoneyInvariants(long seed) {
        Account account = new Account(new Random(seed));
        for (int step = 0; step < STEPS; step++) {
            account.randomStep();
            account.assertInvariants();
        }
    }

    /** One user's Futures wallet with one position slot per pair. */
    private static final class Account {

        private final Random random;
        private final List<BigDecimal> ledger = new ArrayList<>();
        private final Position[] positions = new Position[PAIRS];
        private final BigDecimal[] marks = new BigDecimal[PAIRS];
        private BigDecimal wallet = BigDecimal.ZERO;

        Account(Random random) {
            this.random = random;
            for (int pair = 0; pair < PAIRS; pair++) {
                marks[pair] = new BigDecimal(1000 + random.nextInt(60000)).setScale(2, RoundingMode.UNNECESSARY);
            }
            post(new BigDecimal("5000"));
        }

        void randomStep() {
            int pair = random.nextInt(PAIRS);
            switch (random.nextInt(9)) {
                case 0 -> post(new BigDecimal(1 + random.nextInt(2000)));
                case 1, 2 -> open(pair);
                case 3 -> reduce(pair);
                case 4 -> adjustMargin(pair);
                case 5 -> funding(pair);
                default -> moveMark(pair);
            }
        }

        // ------------------------------------------------------------------ operations

        private void open(int pair) {
            Position p = positions[pair];
            MarginType type = p != null ? p.type : (random.nextBoolean() ? MarginType.ISOLATED : MarginType.CROSSED);
            int leverage = p != null ? p.leverage : 1 + random.nextInt(50);
            OrderSide side = p != null && p.quantity.signum() != 0
                    ? (p.quantity.signum() > 0 ? OrderSide.BUY : OrderSide.SELL)
                    : (random.nextBoolean() ? OrderSide.BUY : OrderSide.SELL);
            BigDecimal quantity = randomQuantity();
            BigDecimal price = nearMark(pair);
            BigDecimal mark = marks[pair];
            BigDecimal cost = FuturesMarginMath.orderCost(side, quantity, price, mark, mark, leverage);
            FuturesAccountMargin margin = margin();
            BigDecimal budget = type == MarginType.ISOLATED ? margin.crossFreeCash() : margin.availableBalance();
            if (cost.compareTo(budget) > 0) {
                return;
            }
            BigDecimal add = type == MarginType.ISOLATED
                    ? FuturesMarginMath.isolatedFillMargin(
                            PositionDirection.openedBy(side), quantity, price, mark, leverage)
                    : BigDecimal.ZERO;
            if (add.compareTo(margin().crossFreeCash()) > 0) {
                return; // checked again at the fill: refused, nothing moves
            }
            BigDecimal before = p == null ? BigDecimal.ZERO : p.quantity;
            PositionFill fill = FuturesMarginMath.applyFill(before, p == null ? null : p.entry, side, quantity, price);
            if (p == null) {
                p = new Position(type, leverage);
                positions[pair] = p;
            }
            p.quantity = fill.quantityAfter();
            p.entry = fill.entryPriceAfter();
            if (type == MarginType.ISOLATED) {
                BigDecimal crossBefore = crossWallet();
                adjust(p, add);
                assertThat(crossBefore.signum() < 0 || crossWallet().signum() >= 0)
                        .as("funding an isolated wallet keeps the cross wallet at or above zero")
                        .isTrue();
            }
        }

        private void reduce(int pair) {
            Position p = positions[pair];
            if (p == null || p.quantity.signum() == 0) {
                return;
            }
            BigDecimal size = p.quantity.abs();
            BigDecimal quantity =
                    random.nextInt(3) == 0 ? size : randomQuantity().min(size);
            close(pair, p, quantity, nearMark(pair), BigDecimal.ZERO);
        }

        private void close(int pair, Position p, BigDecimal quantity, BigDecimal price, BigDecimal feeRate) {
            BigDecimal size = p.quantity.abs();
            OrderSide side = p.quantity.signum() > 0 ? OrderSide.SELL : OrderSide.BUY;
            PositionFill fill = FuturesMarginMath.applyFill(p.quantity, p.entry, side, quantity, price);
            BigDecimal realized = fill.realizedPnl();
            if (p.type == MarginType.ISOLATED) {
                BigDecimal fee = FuturesMarginMath.liquidationFee(
                        quantity.multiply(price), feeRate, p.isolatedWallet.add(realized));
                BigDecimal crossBefore = crossWallet();
                IsolatedCashFlow flow = FuturesMarginMath.closeFlow(p.isolatedWallet, quantity, size, realized, fee);
                post(realized);
                post(fee.negate());
                post(flow.insuranceCover());
                adjust(p, flow.isolatedWalletChange());
                assertThat(crossWallet().subtract(crossBefore))
                        .as("the loss of an isolated position never lowers the cross wallet")
                        .isEqualByComparingTo(flow.crossChange())
                        .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            } else {
                post(realized);
            }
            p.quantity = fill.quantityAfter();
            p.entry = fill.entryPriceAfter();
            if (p.quantity.signum() == 0) {
                assertThat(p.isolatedWallet)
                        .as("a closed position has no isolated wallet")
                        .isZero();
                positions[pair] = null;
                coverCrossWhenNoCrossPositionIsLeft();
            }
        }

        private void adjustMargin(int pair) {
            Position p = positions[pair];
            if (p == null || p.type != MarginType.ISOLATED || p.quantity.signum() == 0) {
                return;
            }
            BigDecimal amount = new BigDecimal(1 + random.nextInt(500));
            if (random.nextBoolean()) {
                if (amount.compareTo(margin().crossFreeCash()) <= 0) {
                    BigDecimal crossBefore = crossWallet();
                    adjust(p, amount);
                    assertThat(crossBefore.signum() < 0 || crossWallet().signum() >= 0)
                            .isTrue();
                }
            } else {
                BigDecimal removable = FuturesMarginMath.maxRemovableMargin(
                        p.isolatedWallet,
                        p.unrealized(marks[pair]),
                        p.quantity.abs().multiply(marks[pair]),
                        p.leverage);
                BigDecimal taken = amount.min(removable);
                if (taken.signum() > 0) {
                    adjust(p, taken.negate());
                }
            }
        }

        private void funding(int pair) {
            Position p = positions[pair];
            if (p == null || p.quantity.signum() == 0) {
                return;
            }
            BigDecimal rate = new BigDecimal(random.nextInt(4001) - 2000).movePointLeft(5);
            BigDecimal fee = FuturesMarginMath.fundingFee(p.direction(), p.quantity.abs(), marks[pair], rate);
            if (fee.signum() == 0) {
                return;
            }
            if (p.type == MarginType.ISOLATED) {
                BigDecimal crossBefore = crossWallet();
                IsolatedCashFlow flow = FuturesMarginMath.fundingFlow(p.isolatedWallet, fee);
                post(fee);
                post(flow.insuranceCover());
                adjust(p, flow.isolatedWalletChange());
                assertThat(crossWallet())
                        .as("funding of an isolated position leaves the cross wallet")
                        .isEqualByComparingTo(crossBefore);
            } else {
                post(fee);
            }
            liquidateIfDue(pair);
        }

        private void moveMark(int pair) {
            BigDecimal move = new BigDecimal(random.nextInt(4001) - 2000).movePointLeft(4); // ±20 %
            marks[pair] = marks[pair]
                    .multiply(BigDecimal.ONE.add(move))
                    .setScale(2, RoundingMode.HALF_UP)
                    .max(TICK);
            for (int each = 0; each < PAIRS; each++) {
                liquidateIfDue(each);
            }
        }

        /** Isolated: at the mark past the liquidation price. Cross: the worst position first while MB ≤ MM. */
        private void liquidateIfDue(int pair) {
            Position p = positions[pair];
            if (p == null || p.quantity.signum() == 0) {
                return;
            }
            if (p.type == MarginType.ISOLATED) {
                BigDecimal price = FuturesMarginMath.liquidationPrice(
                        p.direction(),
                        p.quantity.abs(),
                        p.entry,
                        p.isolatedWallet,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        TIERS,
                        TICK);
                boolean reached =
                        p.quantity.signum() > 0 ? marks[pair].compareTo(price) <= 0 : marks[pair].compareTo(price) >= 0;
                if (reached) {
                    close(pair, p, p.quantity.abs(), marks[pair], LIQUIDATION_FEE_RATE);
                }
                return;
            }
            while (crossMarginBalance().compareTo(crossMaintenance()) <= 0 && worstCross() >= 0) {
                int worst = worstCross();
                Position w = positions[worst];
                close(worst, w, w.quantity.abs(), marks[worst], BigDecimal.ZERO);
            }
        }

        // ------------------------------------------------------------------ bookkeeping

        private void post(BigDecimal amount) {
            if (amount.signum() != 0) {
                ledger.add(amount);
                wallet = wallet.add(amount);
            }
        }

        private void adjust(Position p, BigDecimal amount) {
            if (amount.signum() != 0) {
                p.adjustments.add(amount);
                p.isolatedWallet = p.isolatedWallet.add(amount);
            }
        }

        private void coverCrossWhenNoCrossPositionIsLeft() {
            if (worstCross() < 0 && crossWallet().signum() < 0) {
                post(crossWallet().negate());
            }
        }

        private BigDecimal crossWallet() {
            BigDecimal isolated = BigDecimal.ZERO;
            for (Position p : positions) {
                if (p != null && p.type == MarginType.ISOLATED) {
                    isolated = isolated.add(p.isolatedWallet);
                }
            }
            return wallet.subtract(isolated);
        }

        private BigDecimal crossUnrealized() {
            BigDecimal sum = BigDecimal.ZERO;
            for (int pair = 0; pair < PAIRS; pair++) {
                Position p = positions[pair];
                if (p != null && p.type == MarginType.CROSSED && p.quantity.signum() != 0) {
                    sum = sum.add(p.unrealized(marks[pair]));
                }
            }
            return sum;
        }

        private BigDecimal crossMarginBalance() {
            return crossWallet().add(crossUnrealized());
        }

        private BigDecimal crossMaintenance() {
            BigDecimal sum = BigDecimal.ZERO;
            for (int pair = 0; pair < PAIRS; pair++) {
                Position p = positions[pair];
                if (p != null && p.type == MarginType.CROSSED && p.quantity.signum() != 0) {
                    BigDecimal notional = p.quantity.abs().multiply(marks[pair]);
                    sum = sum.add(
                            FuturesMarginMath.bracketFor(TIERS, notional).isPresent()
                                    ? FuturesMarginMath.maintenanceMargin(TIERS, notional)
                                    : notional.multiply(new BigDecimal("0.0065")));
                }
            }
            return sum;
        }

        private int worstCross() {
            int worst = -1;
            BigDecimal lowest = null;
            for (int pair = 0; pair < PAIRS; pair++) {
                Position p = positions[pair];
                if (p != null && p.type == MarginType.CROSSED && p.quantity.signum() != 0) {
                    BigDecimal u = p.unrealized(marks[pair]);
                    if (lowest == null || u.compareTo(lowest) < 0) {
                        lowest = u;
                        worst = pair;
                    }
                }
            }
            return worst;
        }

        private FuturesAccountMargin margin() {
            BigDecimal crossInitial = BigDecimal.ZERO;
            for (int pair = 0; pair < PAIRS; pair++) {
                Position p = positions[pair];
                if (p != null && p.type == MarginType.CROSSED && p.quantity.signum() != 0) {
                    crossInitial = crossInitial.add(
                            FuturesMarginMath.initialMargin(p.quantity.abs().multiply(marks[pair]), p.leverage));
                }
            }
            return FuturesMarginMath.accountMargin(crossWallet(), crossUnrealized(), crossInitial, BigDecimal.ZERO);
        }

        private BigDecimal randomQuantity() {
            return STEP.multiply(new BigDecimal(1 + random.nextInt(300)));
        }

        private BigDecimal nearMark(int pair) {
            BigDecimal move = new BigDecimal(random.nextInt(201) - 100).movePointLeft(4); // ±1 %
            return marks[pair]
                    .multiply(BigDecimal.ONE.add(move))
                    .setScale(2, RoundingMode.HALF_UP)
                    .max(TICK);
        }

        // ------------------------------------------------------------------ invariants

        void assertInvariants() {
            assertThat(ledger.stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                    .as("the ledger sums to the wallet balance")
                    .isEqualByComparingTo(wallet);
            for (Position p : positions) {
                if (p == null) {
                    continue;
                }
                assertThat(p.isolatedWallet)
                        .as("an isolated wallet is never negative")
                        .isGreaterThanOrEqualTo(BigDecimal.ZERO);
                assertThat(p.adjustments.stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                        .as("an isolated wallet is the sum of its adjustments")
                        .isEqualByComparingTo(p.isolatedWallet);
                if (p.type == MarginType.CROSSED) {
                    assertThat(p.isolatedWallet).isZero();
                }
            }
            if (worstCross() < 0) {
                assertThat(crossWallet())
                        .as("no cross position left: the cross wallet is not negative")
                        .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            }
        }
    }

    private static final class Position {

        private final MarginType type;
        private final int leverage;
        private final List<BigDecimal> adjustments = new ArrayList<>();
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal entry;
        private BigDecimal isolatedWallet = BigDecimal.ZERO;

        Position(MarginType type, int leverage) {
            this.type = type;
            this.leverage = leverage;
        }

        PositionDirection direction() {
            return quantity.signum() > 0 ? PositionDirection.LONG : PositionDirection.SHORT;
        }

        BigDecimal unrealized(BigDecimal mark) {
            return FuturesMarginMath.unrealizedPnl(direction(), quantity.abs(), entry, mark);
        }
    }
}
