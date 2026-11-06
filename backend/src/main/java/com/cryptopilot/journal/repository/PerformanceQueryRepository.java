package com.cryptopilot.journal.repository;

import com.cryptopilot.journal.model.EquityPoint;
import com.cryptopilot.journal.model.PerformanceCounts;
import com.cryptopilot.journal.model.PerformanceDetail;
import com.cryptopilot.journal.model.PerformanceFilter;
import com.cryptopilot.journal.model.PerformanceGroup;
import com.cryptopilot.journal.model.enums.PerformanceMarket;
import com.cryptopilot.journal.model.enums.PerformanceSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The performance statistics, aggregated by PostgreSQL over the closed trades; no trade is loaded here. A closed
 * record without a realized P/L (the schema demands it of a simulated one only) has no result and is left out; a missing
 * fee counts as 0, like missing funding (D-139). Ratios and means are rounded to 8 decimals, the scale of the amounts; a division by zero yields {@code null}, as does a mean
 * or an extreme of nothing. A trade's R uses the planned risk, or without one the risk of its original stop loss, so
 * moving a stop never changes it; a trade without a stop loss is left out of every R metric.
 *
 * <p>Rule: UC-25; BR-38, BR-42, BR-70; D-97, D-111, D-124, D-139; TECHNICAL_DESIGN 7.8, 7.10.
 *
 * <p>Reference: Tharp, V. K. (2007). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill (R-multiples,
 * expectancy). Schwager, J. D. (1989). <i>Market Wizards</i>. New York Institute of Finance (profit factor).
 * Magdon-Ismail, M. &amp; Atiya, A. (2004). "Maximum Drawdown". <i>Risk</i> 17(10). Sweeney, J. (1996). <i>Maximum
 * Adverse Excursion</i>. Wiley (MAE, MFE).
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PerformanceQueryRepository {

    /** The one SQL expression of the net P/L, which has no column (D-97). */
    private static final String NET_PNL = "realized_pnl - coalesce(fee_amount, 0) + coalesce(funding_fee_amount, 0)";

    private static final String COUNTS = """
            count(*) as trades,
            count(*) filter (where net_pnl > 0) as wins,
            count(*) filter (where net_pnl < 0) as losses,
            count(*) filter (where net_pnl = 0) as breakeven,
            round((count(*) filter (where net_pnl > 0))::numeric / nullif(count(*) filter (where net_pnl <> 0), 0), 8)
                as win_rate,
            round(coalesce(sum(net_pnl), 0), 8) as total_net_pnl,
            count(*) filter (where funding_incomplete) as provisional_trades,
            round(coalesce(sum(fee_amount), 0), 8) as total_fees,
            round(coalesce(sum(funding_fee_amount), 0), 8) as total_funding""";

    private static final String PROFIT_FACTOR = """
            round(coalesce(sum(net_pnl) filter (where net_pnl > 0), 0)
                  / nullif(abs(sum(net_pnl) filter (where net_pnl < 0)), 0), 8) as profit_factor""";

    private static final String AVERAGE_R = "round(avg(realized_r), 8) as average_realized_r";

    private final JdbcClient sql;

    /** The metrics of every plan. */
    public PerformanceCounts counts(PerformanceFilter filter) {
        Map<String, Object> params = new HashMap<>();
        return sql.sql(trades(filter, params) + "select " + COUNTS + " from trades")
                .params(params)
                .query((row, index) -> counts(row))
                .single();
    }

    /**
     * Every metric of the summary. {@code streak} is the gaps-and-islands key: the count of trades that are not losses
     * so far stays constant along a run of losses, and needs no second sort. Any such trade, breakeven included, ends a
     * run.
     */
    public PerformanceDetail detail(PerformanceFilter filter) {
        Map<String, Object> params = new HashMap<>();
        String query = trades(filter, params) + """
                , sequenced as (
                    select net_pnl,
                           sum(net_pnl) over ordered as equity,
                           row_number() over ordered as position,
                           count(*) filter (where net_pnl >= 0) over ordered as streak
                      from trades
                    window ordered as (order by exit_time, journal_id)
                ), peaks as (
                    select equity, max(equity) over (order by position) as peak from sequenced
                )
                select %s, %s, %s,
                       round(avg(net_pnl) filter (where net_pnl > 0), 8) as average_win,
                       round(avg(net_pnl) filter (where net_pnl < 0), 8) as average_loss,
                       round(avg(planned_reward_risk), 8) as average_planned_risk_reward,
                       count(realized_r) as trades_with_r,
                       round(max(net_pnl) filter (where net_pnl > 0), 8) as largest_win,
                       round(min(net_pnl) filter (where net_pnl < 0), 8) as largest_loss,
                       round(avg(entry_deviation_r), 8) as average_entry_deviation_r,
                       sum(entry_deviation_r) as cost_of_late_entry_r,
                       round(avg(mae_r), 8) as average_mae_r,
                       round(avg(mfe_r), 8) as average_mfe_r,
                       (select round(max(greatest(peak, 0) - equity), 8) from peaks) as max_drawdown,
                       (select coalesce(max(run), 0)
                          from (select count(*) as run from sequenced where net_pnl < 0 group by streak) runs)
                           as max_consecutive_losses
                  from trades""".formatted(COUNTS, PROFIT_FACTOR, AVERAGE_R);
        return sql.sql(query).params(params).query((row, index) -> detail(row)).single();
    }

    /** One group per pair, the highest total first. */
    public List<PerformanceGroup> byPair(PerformanceFilter filter) {
        return groups(filter, "pair_id::text");
    }

    /** One group per strategy, the trades without one forming a group of their own, the highest total first. */
    public List<PerformanceGroup> byStrategy(PerformanceFilter filter) {
        return groups(filter, "strategy_id::text");
    }

    /** One group per market, the highest total first. */
    public List<PerformanceGroup> byMarket(PerformanceFilter filter) {
        return groups(filter, "market_type");
    }

    /** One point per day with an exit in {@code zone}, oldest first. */
    public List<EquityPoint> equityCurve(PerformanceFilter filter, String zone) {
        Map<String, Object> params = new HashMap<>();
        params.put("zone", zone);
        String query = trades(filter, params) + """
                select day, trades, net_pnl, sum(net_pnl) over (order by day) as cumulative_net_pnl
                  from (select (exit_time at time zone :zone)::date as day, count(*) as trades, sum(net_pnl) as net_pnl
                          from trades
                         group by day) days
                 order by day""";
        return sql.sql(query)
                .params(params)
                .query((row, index) -> new EquityPoint(
                        row.getObject("day", LocalDate.class),
                        row.getLong("trades"),
                        row.getBigDecimal("net_pnl"),
                        row.getBigDecimal("cumulative_net_pnl")))
                .list();
    }

    private List<PerformanceGroup> groups(PerformanceFilter filter, String keyColumn) {
        Map<String, Object> params = new HashMap<>();
        String query = trades(filter, params) + "select " + keyColumn + " as group_key, " + COUNTS + ", "
                + PROFIT_FACTOR + ", " + AVERAGE_R + " from trades group by group_key"
                + " order by total_net_pnl desc, group_key";
        return sql.sql(query)
                .params(params)
                .query((row, index) -> new PerformanceGroup(
                        row.getString("group_key"),
                        counts(row),
                        row.getBigDecimal("profit_factor"),
                        row.getBigDecimal("average_realized_r")))
                .list();
    }

    /** The {@code with} clause naming the filtered trades and their per-trade ratios (TECHNICAL_DESIGN 7.8). */
    private static String trades(PerformanceFilter filter, Map<String, Object> params) {
        StringBuilder where = new StringBuilder("user_id = :userId and exit_time < :to");
        params.put("userId", filter.userId());
        params.put("to", Timestamp.from(filter.to()));
        if (filter.from() != null) {
            where.append(" and exit_time >= :from");
            params.put("from", Timestamp.from(filter.from()));
        }
        if (filter.market() != PerformanceMarket.ALL) {
            where.append(" and market_type = :market");
            params.put("market", filter.market().name());
        }
        if (filter.source() != PerformanceSource.ALL) {
            where.append(" and source = :source");
            params.put("source", filter.source().name());
        }
        if (filter.pairId() != null) {
            where.append(" and pair_id = :pairId");
            params.put("pairId", filter.pairId());
        }
        if (filter.strategyId() != null) {
            where.append(" and strategy_id = :strategyId");
            params.put("strategyId", filter.strategyId());
        }
        return """
                with levels as (
                    select journal_id, pair_id, strategy_id, market_type, direction, quantity, entry_price, exit_time,
                           risk_amount, entry_deviation_r, max_adverse_price, max_favorable_price, fee_amount,
                           funding_fee_amount, funding_incomplete,
                           %s as net_pnl,
                           coalesce(original_stop_loss_price, stop_loss_price) as stop_level,
                           coalesce(original_take_profit_price, take_profit_price) as target_level,
                           coalesce(planned_entry_price, entry_price) as plan_entry
                      from trading_journal
                     where trade_status = 'CLOSED' and realized_pnl is not null and %s
                ), risked as (
                    select *,
                           nullif(coalesce(risk_amount, abs(entry_price - stop_level) * quantity), 0) as risk
                      from levels
                ), trades as (
                    select *,
                           net_pnl / risk as realized_r,
                           abs(target_level - plan_entry) / nullif(abs(plan_entry - stop_level), 0)
                               as planned_reward_risk,
                           case direction when 'LONG' then entry_price - max_adverse_price
                                          else max_adverse_price - entry_price end * quantity / risk as mae_r,
                           case direction when 'LONG' then max_favorable_price - entry_price
                                          else entry_price - max_favorable_price end * quantity / risk as mfe_r
                      from risked
                )
                """.formatted(NET_PNL, where);
    }

    private static PerformanceCounts counts(ResultSet row) throws SQLException {
        return new PerformanceCounts(
                row.getLong("trades"),
                row.getLong("wins"),
                row.getLong("losses"),
                row.getLong("breakeven"),
                row.getBigDecimal("win_rate"),
                row.getBigDecimal("total_net_pnl"),
                row.getLong("provisional_trades"),
                row.getBigDecimal("total_fees"),
                row.getBigDecimal("total_funding"));
    }

    private static PerformanceDetail detail(ResultSet row) throws SQLException {
        return new PerformanceDetail(
                counts(row),
                row.getBigDecimal("average_win"),
                row.getBigDecimal("average_loss"),
                row.getBigDecimal("profit_factor"),
                row.getBigDecimal("average_planned_risk_reward"),
                row.getBigDecimal("average_realized_r"),
                row.getLong("trades_with_r"),
                row.getBigDecimal("largest_win"),
                row.getBigDecimal("largest_loss"),
                row.getLong("max_consecutive_losses"),
                row.getBigDecimal("max_drawdown"),
                row.getBigDecimal("average_entry_deviation_r"),
                row.getBigDecimal("cost_of_late_entry_r"),
                row.getBigDecimal("average_mae_r"),
                row.getBigDecimal("average_mfe_r"));
    }
}
