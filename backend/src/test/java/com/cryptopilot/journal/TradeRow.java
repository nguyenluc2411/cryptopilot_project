package com.cryptopilot.journal;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A closed journal row written straight to the table, for the statistics tests: a manual Spot LONG of quantity 1 at
 * 100 unless a method says otherwise. The rows are the shape the closing code leaves; only the amounts under test vary.
 */
public final class TradeRow {

    private final UUID user;
    private final UUID pair;
    private final Instant exit;
    private final Map<String, Object> values = new HashMap<>();

    private TradeRow(UUID user, UUID pair, Instant exit) {
        this.user = user;
        this.pair = pair;
        this.exit = exit;
        values.put("market", "SPOT");
        values.put("direction", "LONG");
        values.put("leverage", null);
        values.put("entry", new BigDecimal("100"));
        values.put("quantity", BigDecimal.ONE);
        values.put("source", "MANUAL");
        values.put("plan", null);
        values.put("strategy", null);
        values.put("status", "CLOSED");
        values.put("reason", "MANUAL");
        values.put("realized", BigDecimal.ZERO);
        values.put("fee", BigDecimal.ZERO);
        values.put("funding", null);
        values.put("incomplete", false);
        for (String nullable : new String[] {
            "stop", "take", "originalStop", "originalTake", "risk", "plannedEntry", "deviation", "adverse", "favorable"
        }) {
            values.put(nullable, null);
        }
    }

    /** A closed trade with this exit. */
    public static TradeRow closed(UUID user, UUID pair, Instant exit) {
        return new TradeRow(user, pair, exit);
    }

    /** The gross result and the fees; the net P/L is their difference, plus any funding. */
    public TradeRow amounts(String realized, String fee) {
        values.put("realized", new BigDecimal(realized));
        values.put("fee", new BigDecimal(fee));
        return this;
    }

    /** Without the amounts, as a manual record may be. */
    public TradeRow noAmounts() {
        values.put("realized", null);
        values.put("fee", null);
        return this;
    }

    /** Without a fee, as a manual record may be. */
    public TradeRow noFee() {
        values.put("fee", null);
        return this;
    }

    public TradeRow funding(String funding) {
        values.put("funding", new BigDecimal(funding));
        return this;
    }

    public TradeRow fundingIncomplete() {
        values.put("incomplete", true);
        return this;
    }

    /** A Futures trade at leverage 5. */
    public TradeRow futures(String direction) {
        values.put("market", "FUTURES");
        values.put("direction", direction);
        values.put("leverage", 5);
        return this;
    }

    public TradeRow entry(String price, String quantity) {
        values.put("entry", new BigDecimal(price));
        values.put("quantity", new BigDecimal(quantity));
        return this;
    }

    /** The stop loss at the fill, which is also the current one. */
    public TradeRow stop(String price) {
        values.put("stop", new BigDecimal(price));
        values.put("originalStop", new BigDecimal(price));
        return this;
    }

    /** A stop loss moved after the fill. */
    public TradeRow movedStop(String current) {
        values.put("stop", new BigDecimal(current));
        return this;
    }

    public TradeRow take(String price) {
        values.put("take", new BigDecimal(price));
        values.put("originalTake", new BigDecimal(price));
        return this;
    }

    /** A take profit moved after the fill. */
    public TradeRow movedTake(String current) {
        values.put("take", new BigDecimal(current));
        return this;
    }

    public TradeRow risk(String amount) {
        values.put("risk", new BigDecimal(amount));
        return this;
    }

    public TradeRow plannedEntry(String price) {
        values.put("plannedEntry", new BigDecimal(price));
        return this;
    }

    public TradeRow deviation(String r) {
        values.put("deviation", new BigDecimal(r));
        return this;
    }

    public TradeRow excursions(String adverse, String favorable) {
        values.put("adverse", new BigDecimal(adverse));
        values.put("favorable", new BigDecimal(favorable));
        return this;
    }

    public TradeRow reason(String reason) {
        values.put("reason", reason);
        return this;
    }

    public TradeRow strategy(UUID strategy) {
        values.put("strategy", strategy);
        return this;
    }

    /** A simulated trade of this plan. */
    public TradeRow simulated(UUID plan) {
        values.put("source", "SIMULATED");
        values.put("plan", plan);
        return this;
    }

    /** Writes the row. */
    public void insert(JdbcClient sql) {
        Timestamp at = Timestamp.from(exit);
        sql.sql("""
                        insert into trading_journal (journal_id, user_id, pair_id, plan_id, strategy_id, source,
                                                     market_type, direction, leverage, entry_price, stop_loss_price,
                                                     take_profit_price, original_stop_loss_price,
                                                     original_take_profit_price, exit_price, quantity, entry_time,
                                                     exit_time, close_reason, fee_amount, funding_fee_amount,
                                                     funding_incomplete, realized_pnl, risk_amount,
                                                     planned_entry_price, entry_deviation_r, max_adverse_price,
                                                     max_favorable_price, trade_status, created_at, updated_at, version)
                        values (:id, :user, :pair, :plan, :strategy, :source, :market, :direction, :leverage, :entry,
                                :stop, :take, :originalStop, :originalTake, :entry, :quantity, :entryTime, :exitTime,
                                :reason, :fee, :funding, :incomplete, :realized, :risk, :plannedEntry, :deviation,
                                :adverse, :favorable, :status, :exitTime, :exitTime, 1)""")
                .params(values)
                .param("id", UUID.randomUUID())
                .param("user", user)
                .param("pair", pair)
                .param("entryTime", Timestamp.from(exit.minusSeconds(3600)))
                .param("exitTime", at)
                .update();
    }
}
