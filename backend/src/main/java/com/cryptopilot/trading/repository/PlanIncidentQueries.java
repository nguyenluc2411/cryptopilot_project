package com.cryptopilot.trading.repository;

import com.cryptopilot.market.model.enums.MarketType;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Who the notices of a market incident reach among the plans' Traders (D-158): those with a plan waiting on the
 * market or a plan position still open there. Plain SQL through {@link JdbcClient}, a read-only lookup.
 *
 * <p>Rule: NSF-03; D-158.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PlanIncidentQueries {

    private final JdbcClient sql;

    /** The Traders with an ACTIVE plan on the market, or an executed one whose simulated position is open. */
    public List<UUID> tradersOn(MarketType market) {
        return sql.sql("""
                        select distinct p.user_id from trading_plan p
                         where p.market_type = :market
                           and (p.plan_status = 'ACTIVE'
                                or exists (select 1 from trading_journal j
                                            where j.plan_id = p.plan_id and j.trade_status = 'OPEN'
                                              and j.source = 'SIMULATED'))""").param("market", market.name()).query(UUID.class).list();
    }
}
