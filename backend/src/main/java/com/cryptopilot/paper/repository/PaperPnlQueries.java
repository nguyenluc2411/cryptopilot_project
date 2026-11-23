package com.cryptopilot.paper.repository;

import com.cryptopilot.paper.model.enums.WalletType;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The figures of the PnL analysis (TR-28) the entities do not hold as such: the transfers between the wallets by day,
 * the net result of each Futures position closed, and what each Spot sale realised. Plain SQL through {@link JdbcClient}: read-only aggregates.
 *
 * <p>Rule: TR-28; plan section 8.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperPnlQueries {

    private final JdbcClient sql;

    /**
     * What came into a wallet by transfer, less what left it, on each UTC day from {@code from} on, in USDT: a transfer
     * moves worth between the wallets and is not a gain or a loss of either. Only USDT is transferred (the Futures
     * wallet holds USDT only).
     */
    @Transactional(readOnly = true)
    public Map<LocalDate, BigDecimal> transfersIn(UUID userId, WalletType wallet, LocalDate from) {
        Map<LocalDate, BigDecimal> byDay = new HashMap<>();
        sql.sql("""
                        select (e.created_at at time zone 'UTC')::date as day, sum(e.amount) as amount
                          from wallet_ledger_entry e
                          join coin c on c.coin_id = e.coin_id
                         where e.user_id = :userId and e.wallet_type = :wallet and e.entry_type = 'TRANSFER'
                           and c.symbol = 'USDT' and e.created_at >= :from
                         group by 1""")
                .param("userId", userId)
                .param("wallet", wallet.name())
                .param("from", Timestamp.from(from.atStartOfDay().toInstant(java.time.ZoneOffset.UTC)))
                .query((row, n) -> {
                    byDay.put(row.getObject("day", LocalDate.class), row.getBigDecimal("amount"));
                    return null;
                })
                .list();
        return byDay;
    }

    /**
     * The Futures positions closed from {@code from} on, oldest first, each with its net result: what it realised,
     * plus the funding it received less what it paid, less the liquidation fee and the fees of its fills.
     */
    @Transactional(readOnly = true)
    public List<ClosedTrade> closedPositions(UUID userId, Instant from) {
        return sql.sql("""
                        select p.position_id, p.closed_at,
                               p.realized_pnl_amount + p.funding_amount - p.liquidation_fee_amount
                                   - coalesce((select sum(f.fee_amount) from order_fill f
                                                where f.position_id = p.position_id), 0) as net
                          from futures_position p
                         where p.user_id = :userId and p.position_status = 'CLOSED' and p.closed_at >= :from
                         order by p.closed_at, p.position_id""")
                .param("userId", userId)
                .param("from", Timestamp.from(from))
                .query((row, n) -> new ClosedTrade(
                        row.getObject("position_id", UUID.class),
                        row.getTimestamp("closed_at").toInstant(),
                        row.getBigDecimal("net")))
                .list();
    }

    /**
     * The Spot sale orders that realised from {@code from} on, oldest first, each with what its fills realised
     * together against the average cost (TR-28): a sale whose cost was not known realised nothing known and is left
     * out.
     */
    @Transactional(readOnly = true)
    public List<ClosedTrade> spotSales(UUID userId, Instant from) {
        return sql.sql("""
                        select f.order_id, max(f.traded_at) as closed_at, sum(f.realized_pnl_amount) as net
                          from order_fill f
                         where f.user_id = :userId and f.market_type = 'SPOT' and f.side = 'SELL'
                           and f.realized_pnl_amount is not null and f.traded_at >= :from
                         group by f.order_id
                         order by closed_at, f.order_id""")
                .param("userId", userId)
                .param("from", Timestamp.from(from))
                .query((row, n) -> new ClosedTrade(
                        row.getObject("order_id", UUID.class),
                        row.getTimestamp("closed_at").toInstant(),
                        row.getBigDecimal("net")))
                .list();
    }

    /**
     * A trade closed: a Futures position, or a Spot sale order.
     *
     * @param id the position or the order
     * @param closedAt when it closed, or the sale's last fill
     * @param net its net result in USDT
     */
    public record ClosedTrade(UUID id, Instant closedAt, BigDecimal net) {}
}
