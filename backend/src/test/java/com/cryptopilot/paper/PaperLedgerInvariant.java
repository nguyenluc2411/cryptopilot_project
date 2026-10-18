package com.cryptopilot.paper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Both parts of the ledger invariant of the P6b review (section 4), for one account: every balance equals the sum of
 * its ledger entries and is never negative, and every Spot lock is what the account's working orders need.
 */
public final class PaperLedgerInvariant {

    private PaperLedgerInvariant() {}

    /** Fails naming the rows that break the invariant. */
    public static void assertHolds(JdbcClient sql, UUID account) {
        List<Map<String, Object>> balances = sql.sql("""
                        with sums as (
                            select account_id, wallet_type, coin_id, sum(amount) as ledger_total
                              from paper_ledger_entry
                             group by account_id, wallet_type, coin_id
                        ), last_entry as (
                            select distinct on (account_id, wallet_type, coin_id)
                                   account_id, wallet_type, coin_id, balance_after_amount
                              from paper_ledger_entry
                             order by account_id, wallet_type, coin_id, created_at desc, entry_id desc
                        ), broken as (
                            select b.account_id, b.wallet_type, b.coin_id
                              from paper_balance b
                              left join sums s       using (account_id, wallet_type, coin_id)
                              left join last_entry l using (account_id, wallet_type, coin_id)
                             where b.free_amount + b.locked_amount <> coalesce(s.ledger_total, 0)
                                or (l.balance_after_amount is not null
                                    and l.balance_after_amount <> b.free_amount + b.locked_amount)
                                or b.free_amount < 0 or b.locked_amount < 0
                            union all
                            select s.account_id, s.wallet_type, s.coin_id
                              from sums s
                             where not exists (select 1 from paper_balance b
                                                where b.account_id = s.account_id and b.wallet_type = s.wallet_type
                                                  and b.coin_id = s.coin_id)
                        )
                        select * from broken where account_id = ?""").param(account).query().listOfRows();
        List<Map<String, Object>> locks = sql.sql("""
                        with need as (
                            select o.account_id,
                                   case when o.side = 'BUY' then p.quote_coin_id else p.base_coin_id end as coin_id,
                                   case when o.client_order_id ~ ':(tp|sl)$' then o.list_id end as oco_list,
                                   case when o.side = 'BUY'
                                        then ceil(coalesce(o.limit_price, o.stop_price)
                                                  * (o.orig_quantity - o.executed_quantity) * 1e8) / 1e8
                                        else o.orig_quantity - o.executed_quantity end as amount
                              from paper_order o
                              join crypto_pair p on p.pair_id = o.pair_id
                             where o.market_type = 'SPOT' and o.order_type <> 'MARKET'
                               and o.order_status in ('NEW', 'PARTIALLY_FILLED')
                        ), per_group as (
                            select account_id, coin_id, max(amount) as amount from need where oco_list is not null
                             group by account_id, coin_id, oco_list
                            union all
                            select account_id, coin_id, amount from need where oco_list is null
                        ), expected as (
                            select account_id, coin_id, sum(amount) as locked from per_group
                             group by account_id, coin_id
                        )
                        select b.account_id, b.coin_id, b.locked_amount, coalesce(e.locked, 0) as expected_locked
                          from paper_balance b
                          left join expected e using (account_id, coin_id)
                         where b.wallet_type = 'SPOT' and b.locked_amount <> coalesce(e.locked, 0)
                           and b.account_id = ?""").param(account).query().listOfRows();
        assertThat(balances).as("balances that disagree with their ledger").isEmpty();
        assertThat(locks).as("locked amounts the working orders do not need").isEmpty();
    }
}
