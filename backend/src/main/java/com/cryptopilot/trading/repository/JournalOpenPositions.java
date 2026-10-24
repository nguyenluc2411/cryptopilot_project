package com.cryptopilot.trading.repository;

import com.cryptopilot.market.OpenPositionCheck;
import com.cryptopilot.market.model.enums.MarketType;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The simulated positions of executed plans, as the journal keeps them: an OPEN row of source SIMULATED on the pair's
 * market blocks disabling it (BR-59). Manual journal entries are the Trader's own records, not positions the system
 * holds, and do not count.
 *
 * <p>Rule: BR-59.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class JournalOpenPositions implements OpenPositionCheck {

    /** Whether a simulated trade of the pair on the market is open; the journal is written from T-048 on. */
    private static final String ANY_OPEN = """
            select exists (select 1 from trading_journal
                            where pair_id = ? and market_type = ?
                              and trade_status = 'OPEN' and source = 'SIMULATED')""";

    private final JdbcClient sql;

    @Override
    public boolean anyOpen(UUID pairId, MarketType market) {
        return sql.sql(ANY_OPEN)
                .params(pairId, market.name())
                .query(Boolean.class)
                .single();
    }
}
